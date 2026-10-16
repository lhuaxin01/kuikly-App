package com.example.navi_app.navigation

import com.example.navi_app.navigation.presentation.AccountPresentation
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountPresentationTest {
    @Test fun authLabelsMatchMapTemplateModes() {
        assertEquals("登录", AccountPresentation.primaryLabel(false))
        assertEquals("注册并登录", AccountPresentation.primaryLabel(true))
        assertEquals("没有账号？去注册", AccountPresentation.switchLabel(false))
        assertEquals("已有账号？去登录", AccountPresentation.switchLabel(true))
        assertEquals("登录账号", AccountPresentation.title(false))
        assertEquals("注册账号", AccountPresentation.title(true))
    }

    @Test fun credentialsFollowMapTemplateRules() {
        assertEquals("请输入账号", AccountPresentation.validate("", "123456"))
        assertEquals("请输入密码", AccountPresentation.validate("user", ""))
        assertEquals("密码至少 6 位", AccountPresentation.validate("user", "12345"))
        assertEquals("", AccountPresentation.validate(" user ", "123456"))
    }

    @Test fun profileRequiresBackendIdentityAndPreservesDisplayName() {
        val result = JSONObject()
            .put("ok", true)
            .put("logged_in", true)
            .put("user_id", "u-1")
            .put("username", "user")
            .put("display_name", "导航用户")
        val profile = AccountPresentation.profile(result)
        assertTrue(profile.loggedIn)
        assertEquals("u-1", profile.userId)
        assertEquals("导航用户", profile.displayName)

        assertFalse(AccountPresentation.profile(JSONObject().put("ok", true).put("logged_in", true)).loggedIn)
    }

    @Test fun errorsPreferGatewayMessage() {
        assertEquals("用户名已存在", AccountPresentation.error(JSONObject().put("error", JSONObject().put("message", "用户名已存在"))))
        assertEquals("用户名或密码错误（AUTH_HTTP_401）", AccountPresentation.error(JSONObject().put("code", "AUTH_HTTP_401")))
        assertEquals("认证失败，请重试", AccountPresentation.error(JSONObject()))
    }
}
