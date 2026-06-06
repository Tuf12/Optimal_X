Bundled model:
 - universal_sentence_encoder.tflite  (semantic embeddings)

EmbeddingEngine checks these asset paths in order:
 - models/universal_sentence_encoder.tflite
 - models/use.tflite
 - universal_sentence_encoder.tflite
 - use.tflite

You can replace the bundled file with another compatible USE-style text embedding TFLite model.

Speech-to-text uses Android SpeechRecognizer (Google app when installed). No local ASR models are bundled here.
