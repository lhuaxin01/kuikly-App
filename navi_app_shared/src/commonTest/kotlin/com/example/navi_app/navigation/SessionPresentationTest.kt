package com.example.navi_app.navigation

import com.example.navi_app.navigation.presentation.SessionPresentation
import com.example.navi_app.navigation.protocol.NavigationResponseCodec
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionPresentationTest {
    @Test fun listKeepsSessionMetadataAndSkipsInvalidOrDuplicateIds() {
        val sessions = SessionPresentation.list(JSONObject("""{"ok":true,"response":[
          {"sessionId":" s-1 ","title":"周末出行","status":"idle","createdAt":"created","updatedAt":"updated","lastMessageAt":"last"},
          {"sessionId":"s-2","title":" ","last_message_at":"fallback","preview":"你好"},
          {"title":"无标识"},{"sessionId":"s-1","title":"重复"},null
        ]}"""))
        assertEquals(listOf("s-1", "s-2"), sessions.map { it.id })
        assertEquals("周末出行", sessions[0].title)
        assertEquals("idle", sessions[0].status)
        assertEquals("created", sessions[0].createdAt)
        assertEquals("updated", sessions[0].updatedAt)
        assertEquals("last", sessions[0].lastMessageAt)
        assertEquals("未命名会话", sessions[1].title)
        assertEquals("fallback", sessions[1].updatedAt)
        assertEquals("你好", sessions[1].preview)
    }

    @Test fun duplicatedWireAndModelEventsRenderOneUserAndOneFinalReply() {
        val messages = history(
            event("channel_inbound", payload = JSONObject().put("kind", "new_intent").put("text", "去公园").put("request_id", "r1")),
            event("structured_request", payload = JSONObject().put("kind", "new_intent").put("text", "去公园").put("request_id", "r1")),
            event("user_message", "去公园"),
            event("assistant_message", "我先搜索附近公园"),
            event("structured_response", payload = JSONObject().put("reply", "中间响应")),
            event("channel_outbound", payload = JSONObject().put("reply", "请选择公园")),
            event("channel_outbound", payload = JSONObject().put("reply", "请选择公园"))
        )
        assertEquals(listOf("user", "assistant"), messages.map { it.role })
        assertEquals(listOf("去公园", "请选择公园"), messages.map { it.text })
    }

    @Test fun repeatedUserInputInDifferentTurnsIsNotDeduplicated() {
        val messages = history(event("user_message", "去公园"), event("assistant_message", "去哪一个公园？"),
            event("user_message", "去公园"), event("channel_outbound", payload = JSONObject().put("reply", "请选择地点")))
        assertEquals(4, messages.size)
        assertEquals("去公园", messages[0].text)
        assertEquals("去公园", messages[2].text)
        assertEquals(messages.size, messages.map { it.id }.distinct().size)
    }

    @Test fun actionsUseFriendlyTextAndPreferOriginalRequestOverBackendMarker() {
        val messages = history(
            event("structured_request", payload = JSONObject().put("kind", "modify_task").put("text", "增加途经点：火车站")),
            event("user_message", "[modify_task] {\"destination\":\"secret\"}"),
            event("channel_outbound", payload = JSONObject().put("reply", "已修改路线")),
            event("user_message", "[disambiguation_choice] {\"id\":\"poi-1\"}"),
            event("channel_outbound", payload = JSONObject().put("reply", "已确认地点")))
        assertEquals("增加途经点：火车站", messages[0].text)
        assertEquals("我选择了候选地点", messages[2].text)
        assertFalse(messages.any { it.text.contains("secret") || it.text.contains("poi-1") })
    }

    @Test fun taskOnlyResponseDisplaysRouteAndCoordinatesWithoutRawJson() {
        val task = JSONObject("""{"task":{"stops":[
          {"role":"origin","poi":{"name":"当前位置","location":{"lat":39.9,"lng":116.1}}},
          {"role":"waypoint","text":"火车站"},
          {"role":"destination","poi":{"name":"中央公园","address":"中心路","location":{"latitude":40.12,"longitude":116.2}}}
        ]}}""")
        val messages = history(event("user_message", "经过火车站去公园"), event("assistant_message", "```json\n$task\n```"),
            event("structured_response", payload = task), event("channel_outbound", payload = task))
        assertEquals(2, messages.size)
        assertEquals("起点：当前位置\n位置：39.900000, 116.100000\n\n途经点：火车站\n\n目的地：中央公园\n位置：中心路\n坐标：40.120000, 116.200000", messages[1].text)
        assertFalse(messages[1].text.contains("{", false))
    }

    @Test fun liveAndReloadedRepliesUseTheSameSummary() {
        val payload = JSONObject("""{"status":"ok","response_type":"navigation_task","task":{"stops":[
          {"role":"destination","poi":{"name":"中央公园","address":"中心路","location":{"lat":39.9,"lng":116.1}}}
        ]}}""")
        assertEquals(history(event("channel_outbound", payload = payload))[1].text,
            SessionPresentation.reply(NavigationResponseCodec.decode(payload)))
    }

    @Test fun candidateQuestionAndErrorAreShownInsteadOfProtocolObjects() {
        val messages = history(event("user_message", "去公园"), event("channel_outbound", payload = JSONObject("""
          {"question":"请选择目的地","candidate_groups":[{"items":[{"poi":{"name":"东公园","address":"东路"}},{"poi":{"name":"西公园","address":"西路"}}]}]}
        """)), event("user_message", "去远处"), event("channel_outbound", payload = JSONObject("""
          {"error":{"code":"POI_NOT_FOUND","message":"没有找到该地点"},"task":{"stops":[{"role":"destination","text":"旧地点"}]}}
        """)))
        assertTrue(messages[1].text.startsWith("请选择目的地\n\n候选地点："))
        assertTrue(messages[1].text.contains("候选地点 1：东公园\n位置：东路"))
        assertTrue(messages[1].text.contains("候选地点 2：西公园"))
        assertEquals("没有找到该地点", messages[3].text)
    }

    @Test fun internalEventsAndUnrecognisedJsonAreNeverRendered() {
        val messages = history(event("user_message", "你好"), event("reasoning", "内部思考"),
            event("tool_result", "工具输出"), event("assistant_message", "{\"internal\":\"hidden\"}"),
            event("assistant_message", "{ broken json"))
        assertEquals(listOf("你好", "（暂无助手回复）"), messages.map { it.text })
    }

    @Test fun separatePendingRequestsRemainSeparateAndPreserveTimes() {
        val messages = history(
            event("channel_inbound", payload = JSONObject().put("text", "第一个请求").put("request_id", "a")),
            event("channel_inbound", payload = JSONObject().put("text", "第二个请求").put("request_id", "b")))
        assertEquals(listOf("第一个请求", "（暂无助手回复）", "第二个请求", "（暂无助手回复）"), messages.map { it.text })
        assertEquals("2026-09-28T10:00:00Z", messages[0].timestamp)
    }

    private var nextId = 0
    private fun event(type: String, text: String = "", payload: JSONObject? = null): JSONObject =
        JSONObject().put("id", "e${nextId++}").put("type", type).put("text", text).put("createdAt", "2026-09-28T10:00:00Z")
            .also { if (payload != null) it.put("payload", payload) }

    private fun history(vararg events: JSONObject) = SessionPresentation.history(
        JSONObject().put("events", JSONArray().also { array -> events.forEach { array.put(it) } }))
}
