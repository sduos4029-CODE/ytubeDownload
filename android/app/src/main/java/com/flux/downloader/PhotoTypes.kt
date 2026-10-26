package com.flux.downloader

object PhotoTypes {
    fun mime(extension: String): String = when(extension.lowercase()) {
        "jpg","jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
        else -> error("Unsupported source photo format.")
    }
}

object SourceFailures {
    fun message(url: String, error: String): String? {
        if(!MediaLinks.isInstagram(url))return null
        return when {
            error.contains("429") || error.contains("rate-limit",true) || error.contains("too many requests",true) -> "Instagram is limiting requests. Wait before trying this public post again."
            error.contains("login",true) || error.contains("sign in",true) || error.contains("private",true) -> "This Instagram post requires login or is private. Flux supports accessible public posts without login."
            error.contains("403") -> "Instagram denied access to this post. Try another public post or retry later."
            error.contains("404") || error.contains("empty media response",true) -> "This Instagram post is unavailable or restricted. Check that it opens publicly, then try again."
            else -> null
        }
    }
}
