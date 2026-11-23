package app.pristinepage.ocr

/** Held synthetic images from the authenticated local probe stage; the query anchors are independent of recognized output. */
internal data class OcrScriptControl(val id: String, val bytes: Int, val sha: String, val queries: List<String>)

internal object PinnedOcrScriptControls {
    val latin = listOf(
        OcrScriptControl("spa", 6398, "dff7bd8b6d3b205a5050704d551ec586a117d0d0a9234e59b3df1b1fb91b8ff5", listOf("factura")),
        OcrScriptControl("deu", 8054, "b8d5a7dd9c0bab3ec19f0b855f37ac4b524438ab68055c81c0c4f5878690f9c5", listOf("Rechnung")),
        OcrScriptControl("ita", 5697, "46035fc817cc5c33af9c6e58d261ed7774d05c7f843f06d0c77e28c7a4463f58", listOf("conto")),
        OcrScriptControl("por", 7297, "9e6a7de1cd7e272fc7c8dc02c939cfdd612e28f5b0ad1644104666008b7bbe9d", listOf("compra")),
        OcrScriptControl("nld", 6440, "8915d9b022bc1f27346e7a1266cc8dcf89c5f207b6ec8a777b4b00f9ea40f50e", listOf("totaalbedrag")),
        OcrScriptControl("pol", 7795, "9805791bca2e439b27544b7051d1c83c8b3d52ab91dfd56e77a8b560e817e9bf", listOf("paragonie")),
        OcrScriptControl("tur", 6371, "8ed5422322e78a9642ea8605e7720aa554c3b587e98516b13e37e43992176cb5", listOf("makbuzun")),
    )
    val mixedScripts = listOf(
        OcrScriptControl("rus", 6498, "bb4e69a9f19b598e6a07354c0738e60fe140122bafcecd45723221b958d71c28", listOf("квитанции")),
        OcrScriptControl("ukr", 6776, "67365c6af33bda54bc4bf138c76314faf037642eb23afd588f728216ae2ed04e", listOf("квитанції")),
        OcrScriptControl("ell", 7919, "be60adcf110bd73e2a80419ea1f71717824872858816f1be6932c64e6b799389", listOf("απόδειξη")),
        OcrScriptControl("heb", 4532, "c1fd8c004fe164ec29067f5f2f4381e4926d19b07f71d562fac4e22c8608b7b3", listOf("הקבלה")),
        OcrScriptControl("hin", 7004, "68c115a1fd3cb91a47dc560a86d79b3bd5b286c481f977909bb76e582497d48a", listOf("रसीद")),
        OcrScriptControl("kor", 8000, "ab3c79af045b9286f343762cf9ab98840cc045b8b327bbfbc711709a65fbd1b4", listOf("영수증")),
        OcrScriptControl("vie", 7338, "b9cc2319992a9cecf0bdee79443cce2cc77cd933def6e35170cd928356b8c8cd", listOf("biên lai")),
        OcrScriptControl("ind", 6124, "3425e374f96175824eb80a3845baa9eeaaed5c6184b56c4c810ef820c798b751", listOf("tanda terima")),
    )
}
