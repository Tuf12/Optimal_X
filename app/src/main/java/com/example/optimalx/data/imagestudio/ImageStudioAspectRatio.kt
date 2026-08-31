package com.example.optimalx.data.imagestudio

data class ImageDimensions(
    val width: Int,
    val height: Int,
)

object ImageStudioAspectRatio {
    private val DIMENSIONS: Map<ImageAspectRatio, ImageDimensions> = mapOf(
        ImageAspectRatio.SQUARE to ImageDimensions(1024, 1024),
        ImageAspectRatio.PORTRAIT_2_3 to ImageDimensions(832, 1216),
        ImageAspectRatio.PORTRAIT_9_16 to ImageDimensions(768, 1344),
        ImageAspectRatio.LANDSCAPE_3_2 to ImageDimensions(1216, 832),
        ImageAspectRatio.LANDSCAPE_16_9 to ImageDimensions(1344, 768),
    )

    fun resolve(aspectRatio: ImageAspectRatio): ImageDimensions =
        DIMENSIONS[aspectRatio] ?: DIMENSIONS.getValue(ImageAspectRatio.SQUARE)
}
