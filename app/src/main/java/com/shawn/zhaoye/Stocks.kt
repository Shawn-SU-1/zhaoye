package com.shawn.zhaoye

/**
 * The stock catalogue, generated from the profile JSON files in
 * engine/resources/profiles.
 *
 * A profile with a `target_print` is a film; a slide has no print stage, so
 * `positive` films skip the paper picker. A negative with no target is a paper.
 */
data class FilmStock(
    val id: String,
    val name: String,
    val positive: Boolean,
    val targetPaper: String?,
)

data class PaperStock(val id: String, val name: String)

object Stocks {
    val papers: List<PaperStock> = listOf(
        PaperStock("fujifilm_crystal_archive_typeii", "Fujifilm Crystal Archive Type II"),
        PaperStock("kodak_2383", "Kodak Vision 2383"),
        PaperStock("kodak_2393", "Kodak Vision Premier 2393"),
        PaperStock("kodak_ektacolor_edge", "Kodak Ektacolor Edge"),
        PaperStock("kodak_endura_premier", "Kodak Professional Endura Premier"),
        PaperStock("kodak_portra_endura", "Kodak Professional Portra Endura"),
        PaperStock("kodak_supra_endura", "Kodak Professional Supra Endura"),
        PaperStock("kodak_ultra_endura", "Kodak Professional Ultra Endura"),
    )

    val films: List<FilmStock> = listOf(
        FilmStock("fujifilm_c200", "Fujifilm C200", false, "fujifilm_crystal_archive_typeii"),
        FilmStock("fujifilm_pro_400h", "Fujifilm Pro 400H", false, "fujifilm_crystal_archive_typeii"),
        FilmStock("fujifilm_provia_100f", "Fujifilm Provia 100F", true, null),
        FilmStock("fujifilm_velvia_100", "Fujifilm Velvia 100", true, null),
        FilmStock("fujifilm_xtra_400", "Fujifilm X-Tra 400", false, "fujifilm_crystal_archive_typeii"),
        FilmStock("kodak_ektachrome_100", "Kodak Ektachrome 100", true, null),
        FilmStock("kodak_ektar_100", "Kodak Ektar 100", false, "kodak_portra_endura"),
        FilmStock("kodak_gold_200", "Kodak Gold 200", false, "kodak_portra_endura"),
        FilmStock("kodak_kodachrome_64", "Kodak Kodachrome 64", true, null),
        FilmStock("kodak_portra_160", "Kodak Portra 160", false, "kodak_portra_endura"),
        FilmStock("kodak_portra_400", "Kodak Portra 400", false, "kodak_portra_endura"),
        FilmStock("kodak_portra_800", "Kodak Portra 800", false, "kodak_portra_endura"),
        FilmStock("kodak_portra_800_push1", "Kodak Portra 800 (Push 1)", false, "kodak_portra_endura"),
        FilmStock("kodak_portra_800_push2", "Kodak Portra 800 (Push 2)", false, "kodak_portra_endura"),
        FilmStock("kodak_ultramax_400", "Kodak Ultramax 400", false, "kodak_portra_endura"),
        FilmStock("kodak_verita_200d", "Kodak Verita 200D", false, "kodak_2383"),
        FilmStock("kodak_vision3_200t", "Kodak Vision3 200T", false, "kodak_2383"),
        FilmStock("kodak_vision3_250d", "Kodak Vision3 250D", false, "kodak_2383"),
        FilmStock("kodak_vision3_500t", "Kodak Vision3 500T", false, "kodak_2383"),
        FilmStock("kodak_vision3_50d", "Kodak Vision3 50D", false, "kodak_2383"),
    )

    fun paper(id: String?): PaperStock = papers.firstOrNull { it.id == id } ?: papers.first()
    fun film(id: String): FilmStock = films.firstOrNull { it.id == id } ?: films.first()
}
