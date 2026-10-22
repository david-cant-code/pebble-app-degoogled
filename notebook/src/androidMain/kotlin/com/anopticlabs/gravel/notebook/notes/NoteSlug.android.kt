package com.anopticlabs.gravel.notebook.notes

import java.text.Normalizer

internal actual fun nfkd(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
