package com.flux.downloader

object PhotoTypes {
    fun mime(extension: String): String = when(extension.lowercase()) {
        "jpg","jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
        else -> error("Unsupported source photo format.")
    }
}

object SourceFailures {
    fun message(url: String, error: String): String? {
        if(MediaLinks.isThreads(url))return when {
            error.contains("429") -> "Threads is limiting requests. Wait before trying this public post again."
            error.contains("403") || error.contains("401") -> "Threads denied this request. Retry later or try another public post. No Instagram connection or Flux server is used for Threads."
            else -> "Flux could not retrieve or download this Threads post. It may be limited, unavailable, or its page format changed. Retry the public post later."
        }
        if(!MediaLinks.isInstagram(url))return null
        return when {
            error.contains("Flux server",true) -> error.take(350)
            error.contains("429") || error.contains("rate-limit",true) || error.contains("too many requests",true) -> "Instagram is limiting requests. Wait before trying this public post again."
            error.contains("cookies are no longer valid",true) || error.contains("connection expired",true) -> "Your Instagram connection expired. Reconnect Instagram in Settings, then retry."
            // This extractor error includes generic login/cookie advice. It
            // does not establish that the post is private or unavailable.
            error.contains("empty media response",true) -> "Flux could not retrieve this Instagram post through direct access. No thumbnail or download formats were returned. This request did not use a Flux server. Configure the public Instagram server in Settings; hosting is not included."
            error.contains("login",true) || error.contains("sign in",true) || error.contains("private",true) -> "Instagram requires sign-in for this request. Connect or reconnect Instagram in Settings."
            error.contains("401") -> "Instagram rejected this request. Connect or reconnect Instagram in Settings, then retry."
            error.contains("403") -> "Instagram denied access to this post. Try another public post or retry later."
            error.contains("404") -> "Instagram returned no post data for this request. Flux cannot tell whether the post is unavailable or this extraction method failed."
            else -> null
        }
    }
}
