package com.arabiflow.data

/** Selects only registered, owner-authorized endpoints. No public server scanning. */
data class ProbeResult(val ready: Boolean, val reason: String = "")
data class ServerSelection(val endpoint: ServerEndpoint?, val reason: String)

object ServerChooser {
    suspend fun choose(
        candidates: List<ServerEndpoint>,
        probe: suspend (ServerEndpoint) -> ProbeResult
    ): ServerSelection {
        if (candidates.isEmpty()) return ServerSelection(null,
            "لا يوجد خادم مسجل. أضف خادم معالجة تملكه أو موثوقًا به.")
        val problems = mutableListOf<String>()
        for (candidate in candidates.distinctBy { it.url }.take(4)) {
            if (!ServerConfig.validateOrigin(candidate.url) || candidate.token.isBlank()) {
                problems.add("خادم غير صالح")
                continue
            }
            val result = try { probe(candidate) } catch (_: Exception) {
                ProbeResult(false, "تعذّر الاتصال")
            }
            if (result.ready) return ServerSelection(candidate,
                "تم الاتصال تلقائيًا بالخادم: " + candidate.url)
            problems.add(candidate.url + ": " + result.reason.take(120))
        }
        return ServerSelection(null, problems.joinToString("؛ "))
    }
}
