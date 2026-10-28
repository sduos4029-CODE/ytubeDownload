package com.flux.downloader

object AudioLanguages {
    val choices=listOf("" to "Source default","en" to "English","hi" to "Hindi","ta" to "Tamil","te" to "Telugu",
        "ml" to "Malayalam","kn" to "Kannada","mr" to "Marathi","bn" to "Bengali","gu" to "Gujarati",
        "pa" to "Punjabi","ur" to "Urdu","ar" to "Arabic","es" to "Spanish","fr" to "French",
        "de" to "German","ja" to "Japanese","ko" to "Korean","zh" to "Chinese","en-US" to "English (US)","en-GB" to "English (UK)")
    fun valid(code:String)=code.isEmpty() || code.matches(Regex("[A-Za-z0-9_-]{2,20}"))
    fun label(code:String)=choices.firstOrNull{it.first.equals(code.replace('_','-'),true)}?.second?:"Custom ($code)"
}
