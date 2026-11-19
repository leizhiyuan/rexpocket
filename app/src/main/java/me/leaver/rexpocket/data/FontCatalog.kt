package me.leaver.rexpocket.data

internal const val SYSTEM_FONT = "system"

internal data class TerminalFont(
    val id: String,
    val name: String,
    val source: String,
    val assets: List<FontAsset>,
    val downloadMb: Int,
    val nerd: Boolean = false,
) {
    val cacheKey: String get() = if (id == "jetbrains-mono") "jetbrains-mono-v1" else "$id-v3.4.0"
}

/** Immutable upstream revisions and hashes: never accept an unverified font download. */
internal object FontCatalog {
    private const val NERD = "https://raw.githubusercontent.com/ryanoasis/nerd-fonts/fa7b859994228a9c8759f99c55a8d31ee92a1b5e/patched-fonts"
    val fonts = listOf(
        TerminalFont("jetbrains-mono", "JetBrains Mono",
            "https://raw.githubusercontent.com/JetBrains/JetBrainsMono/19371302b95d218af43299bce79ddbddd0bc364d/fonts/ttf",
            listOf(
                FontAsset("JetBrainsMono-Regular.ttf", "e6fd0d7e91550b3ed2b735d4312474362c4716edc4fc0577a0f61ed782d5aed1"),
                FontAsset("JetBrainsMono-Bold.ttf", "d22c4f3821d725eb01210d278d95dfcfcaadc34699a06658d47c8a5cc5830ada", weight = 700),
                FontAsset("JetBrainsMono-Italic.ttf", "6dc8e1322d4b2013b64e277bf66abc6748207c46a590471d4296876c2c235d0c", italic = true),
                FontAsset("JetBrainsMono-BoldItalic.ttf", "cec9b489af5c98a94cb8d8d8708ef1640cca0f296b207de1f50b2dd95e967bdf", weight = 700, italic = true),
            ), 1),
        TerminalFont("jetbrains-nerd", "JetBrains Mono Nerd Font", "$NERD/JetBrainsMono/Ligatures", listOf(
            FontAsset("JetBrainsMonoNerdFontMono-Regular.ttf", "f01031f40e48dc29e1112e6b0b0450a2c6cd097f3f35cfff05c55cb311f8034c", "Regular"),
            FontAsset("JetBrainsMonoNerdFontMono-Bold.ttf", "5bdd4a873f3cd32f882d2c55545089123926e27707d5880fc9eaf84eb01b6686", "Bold", 700),
            FontAsset("JetBrainsMonoNerdFontMono-Italic.ttf", "ccd88b36d325e6a905edc8dd3f2522718d9690d9bed3fbb4684c7e746c34f846", "Italic", italic = true),
            FontAsset("JetBrainsMonoNerdFontMono-BoldItalic.ttf", "d931df2928b3216892d35980cddcad9edade1b9c9cd2e09a6c2937139f474742", "BoldItalic", 700, true),
        ), 10, nerd = true),
        TerminalFont("fira-code-nerd", "Fira Code Nerd Font", "$NERD/FiraCode", listOf(
            FontAsset("FiraCodeNerdFontMono-Regular.ttf", "ad88c69cb6a497db9f2714e4b414817aabbee621484a1560bfdb3fd73abdd564", "Regular"),
            FontAsset("FiraCodeNerdFontMono-Bold.ttf", "bd014a21f3cd64203dd0850437091d716e929a4518b0e5848d6d3ad6af12b21d", "Bold", 700),
        ), 5, nerd = true),
        TerminalFont("hack-nerd", "Hack Nerd Font", "$NERD/Hack", listOf(
            FontAsset("HackNerdFontMono-Regular.ttf", "03e60d3c1a9f8bef4e1f78836f80aacb9ec005260a6b094f5bfc10043bb115ab", "Regular"),
            FontAsset("HackNerdFontMono-Bold.ttf", "711084fdea9f9eb4e5dbca372a19e6a5af996fc88bfce55918eeef560f0f6722", "Bold", 700),
            FontAsset("HackNerdFontMono-Italic.ttf", "86c6e1b14e2cb02ac8041269c53dac3673c70ff58375d1aafe0ecff8087f8126", "Italic", italic = true),
            FontAsset("HackNerdFontMono-BoldItalic.ttf", "82fca6ff9e87bc65b6abb1bbde1e3884fa9418bfb14f15b9db2d3274b87bf44e", "BoldItalic", 700, true),
        ), 11, nerd = true),
        TerminalFont("meslo-nerd", "Meslo LGL Nerd Font", "$NERD/Meslo/L", listOf(
            FontAsset("MesloLGLNerdFontMono-Regular.ttf", "de03898cc08f9922bc69c98b24c54c69ee0f2e65eabb399a29e2396912a56a51", "Regular"),
            FontAsset("MesloLGLNerdFontMono-Bold.ttf", "175f82836af6bed1fb9b1f1f76d6cf91007b4fe54d4daebb282233058580196e", "Bold", 700),
            FontAsset("MesloLGLNerdFontMono-Italic.ttf", "abc8011f5060f1315146b4a55a3aa7700fd53d07028c243bc2cfe8379bcacc2d", "Italic", italic = true),
            FontAsset("MesloLGLNerdFontMono-BoldItalic.ttf", "51ae8c922457fd0c87a88657afe04fcfbd25585f76a4aefe1b51f50e6dc4700b", "Bold-Italic", 700, true),
        ), 11, nerd = true),
    )

    fun find(id: String): TerminalFont? = fonts.find { it.id == id }
}
