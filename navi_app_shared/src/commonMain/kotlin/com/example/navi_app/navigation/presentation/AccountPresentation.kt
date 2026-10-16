package com.example.navi_app.navigation.presentation

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** MapTemplate account rules. Credentials and networking stay in the platform adapter. */
object AccountPresentation {
    fun primaryLabel(registerMode: Boolean): String = if (registerMode) "注册并登录" else "登录"

    fun switchLabel(registerMode: Boolean): String = if (registerMode) "已有账号？去登录" else "没有账号？去注册"

    fun title(registerMode: Boolean): String = if (registerMode) "注册账号" else "登录账号"

    fun validate(username: String, password: String): String = when {
        username.isBlank() -> "请输入账号"
        password.isBlank() -> "请输入密码"
        password.length < 6 -> "密码至少 6 位"
        else -> ""
    }

    fun profile(result: JSONObject): AuthState = AuthState(
        loggedIn = result.optBoolean("ok", false) && result.optBoolean("logged_in", false) &&
            result.optString("user_id").isNotBlank() && result.optString("username").isNotBlank(),
        username = result.optString("username"),
        displayName = result.optString("display_name"),
        userId = result.optString("user_id")
    )

    fun error(result: JSONObject): String {
        val direct = result.optString("message")
        val nested = result.optJSONObject("error")?.optString("message").orEmpty()
        val code = result.optString("code")
        val message = direct.ifBlank { nested }.ifBlank {
            when (code) {
                "AUTH_HTTP_401" -> "用户名或密码错误"
                "AUTH_HTTP_409" -> "用户名已存在"
                "AUTH_ENDPOINT_NOT_CONFIGURED" -> "navi-claw 登录地址尚未配置"
                "AGENT_NETWORK_ERROR" -> "无法连接 navi-claw，请检查服务地址和网络"
                else -> "认证失败，请重试"
            }
        }
        return if (code.isBlank() || message.contains(code)) message else "$message（$code）"
    }
}
