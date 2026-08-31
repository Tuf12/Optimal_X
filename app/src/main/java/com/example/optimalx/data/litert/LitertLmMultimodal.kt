package com.example.optimalx.data.litert

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import java.io.File

object LitertLmMultimodal {

    fun userMessageContents(text: String, imagePaths: List<String>): Contents {
        val parts = mutableListOf<Content>()
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            parts += Content.Text(trimmed)
        }
        imagePaths.forEach { path ->
            val file = File(path.trim())
            if (file.isFile) {
                parts += Content.ImageFile(file.absolutePath)
            }
        }
        require(parts.isNotEmpty()) { "user message requires text or at least one image path" }
        return Contents.of(parts)
    }

    fun visionPromptContents(imagePath: String, prompt: String): Contents =
        Contents.of(
            Content.Text(prompt.trim()),
            Content.ImageFile(File(imagePath.trim()).absolutePath),
        )

    /** [audioWav] is a WAV container (not raw PCM) — required by LiteRT miniaudio. */
    fun scribePromptContents(audioWav: ByteArray): Contents =
        Contents.of(
            Content.AudioBytes(audioWav),
            Content.Text("Transcribe this audio."),
        )
}
