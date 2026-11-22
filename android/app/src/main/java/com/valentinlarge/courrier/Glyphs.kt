package com.valentinlarge.courrier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Les pictogrammes de l'appli : un trait fin aux bouts ronds, dessiné sur une grille de 24. */
object Glyphs {

    private fun glyph(name: String, vararg paths: String, width: Float = 1.8f): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (path in paths) {
            builder.addPath(
                pathData = addPathNodes(path),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    val Mail by lazy {
        glyph("courrier", "M6 5 h12 a3 3 0 0 1 3 3 v8 a3 3 0 0 1 -3 3 H6 a3 3 0 0 1 -3 -3 V8 a3 3 0 0 1 3 -3 z", "M4 8 l8 6 l8 -6")
    }
    val Clip by lazy {
        glyph(
            "pièce jointe",
            "M20 11.5 L12.5 19 a5 5 0 0 1 -7 -7 L13 4.5 a3.3 3.3 0 0 1 4.7 4.7 l-7.4 7.4 a1.7 1.7 0 0 1 -2.4 -2.4 l6.6 -6.6",
        )
    }
    val Sliders by lazy {
        glyph(
            "réglages",
            "M4 7 h10 M18 7 h2 M4 17 h2 M10 17 h10",
            "M14 7 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
            "M6 17 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
        )
    }
    val Pencil by lazy { glyph("écrire", "M4 20 h4 L19 9 l-4 -4 L4 16 v4 z", "M13.5 6.5 l4 4") }
    val Archive by lazy {
        glyph(
            "archiver",
            "M4.5 4 h15 a1.5 1.5 0 0 1 1.5 1.5 v2 a1.5 1.5 0 0 1 -1.5 1.5 h-15 A1.5 1.5 0 0 1 3 7.5 v-2 A1.5 1.5 0 0 1 4.5 4 z",
            "M5 9 v9 a2 2 0 0 0 2 2 h10 a2 2 0 0 0 2 -2 V9",
            "M10 13 h4",
        )
    }
    val Trash by lazy {
        glyph(
            "corbeille",
            "M4 7 h16",
            "M9 7 V5 a1 1 0 0 1 1 -1 h4 a1 1 0 0 1 1 1 v2",
            "M6 7 l1 12 a2 2 0 0 0 2 2 h6 a2 2 0 0 0 2 -2 l1 -12",
        )
    }
    val Reply by lazy { glyph("répondre", "M10 8 L5 13 l5 5", "M5 13 h9 a5 5 0 0 1 5 5 v1") }
    val ReplyAll by lazy { glyph("répondre à tous", "M8 8 L3 13 l5 5", "M13 8 l-5 5 l5 5", "M8 13 h8 a5 5 0 0 1 5 5 v1") }
    val Forward by lazy { glyph("transférer", "M14 8 l5 5 l-5 5", "M19 13 h-9 a5 5 0 0 0 -5 5 v1") }
    val Plane by lazy { glyph("envoyer", "M21 3 L10 14", "M21 3 l-7 18 l-4 -7 l-7 -4 l18 -7 z") }
    val Folder by lazy {
        glyph("dossier", "M3 7 a2 2 0 0 1 2 -2 h4 l2 2.5 h8 a2 2 0 0 1 2 2 V17 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 V7 z")
    }
    val At by lazy {
        glyph(
            "adresse",
            "M8.5 12 a3.5 3.5 0 1 0 7 0 a3.5 3.5 0 1 0 -7 0",
            "M15.5 8.5 v5 a2.2 2.2 0 0 0 4.4 0 V12 a8 8 0 1 0 -3.2 6.4",
        )
    }
    val Search by lazy { glyph("chercher", "M4.5 11 a6.5 6.5 0 1 0 13 0 a6.5 6.5 0 1 0 -13 0", "M20 20 l-4.2 -4.2") }
    val Close by lazy { glyph("fermer", "M6 6 l12 12", "M18 6 L6 18") }
    val Back by lazy { glyph("retour", "M19 12 H5", "M11 6 l-6 6 l6 6") }
    val More by lazy { glyph("autres actions", "M12 5.5 v0.01", "M12 12 v0.01", "M12 18.5 v0.01", width = 2.6f) }
    val ChevronDown by lazy { glyph("déplier", "M6 9 l6 6 l6 -6", width = 2.2f) }
    val ChevronRight by lazy { glyph("suite", "M9 6 l6 6 l-6 6", width = 2f) }
    val ChevronLeft by lazy { glyph("précédent", "M15 6 l-6 6 l6 6", width = 2f) }
    val Check by lazy { glyph("coché", "M5 12.5 l4.5 4.5 L19 7.5", width = 2.2f) }
    val Plus by lazy { glyph("ajouter", "M12 5 v14", "M5 12 h14") }
    val Padlock by lazy {
        glyph(
            "verrou",
            "M7 11 h10 a2 2 0 0 1 2 2 v5 a2 2 0 0 1 -2 2 H7 a2 2 0 0 1 -2 -2 v-5 a2 2 0 0 1 2 -2 z",
            "M8 11 V8 a4 4 0 0 1 8 0 v3",
        )
    }
    val Moon by lazy { glyph("thème", "M20 14.5 A8 8 0 1 1 9.5 4 a6.5 6.5 0 0 0 10.5 10.5 z") }
    val Funnel by lazy { glyph("tri", "M4 6 h16 l-6 7 v5 l-4 2 v-7 L4 6 z") }
    val Bell by lazy { glyph("notifications", "M6 16 v-5 a6 6 0 0 1 12 0 v5 l1.5 2 h-15 L6 16 z", "M10 20 a2 2 0 0 0 4 0") }
    val Fingerprint by lazy {
        glyph(
            "empreinte",
            "M12 11 v3 a6 6 0 0 1 -1 3.3",
            "M8.5 14 a3.5 3.5 0 0 1 7 0 c0 2 -0.4 4 -1.2 5.6",
            "M5.2 14.5 a6.8 6.8 0 0 1 13.6 -0.8 c0.1 1.7 0 3.3 -0.4 4.8",
            "M6.3 8.2 A8.5 8.5 0 0 1 12 6 c2 0 3.9 0.7 5.4 1.9",
        )
    }
    val Phone by lazy {
        glyph(
            "appareil",
            "M9.5 3 h5 A2.5 2.5 0 0 1 17 5.5 v13 a2.5 2.5 0 0 1 -2.5 2.5 h-5 A2.5 2.5 0 0 1 7 18.5 v-13 A2.5 2.5 0 0 1 9.5 3 z",
            "M11 18 h2",
        )
    }
}
