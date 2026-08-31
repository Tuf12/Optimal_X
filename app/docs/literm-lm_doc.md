LiteRT-LM Kotlin API
The Kotlin API of LiteRT-LM for Android and JVM (Linux, MacOS, Windows)
with features like GPU and NPU acceleration, multi-modality, and
tools use.

Introduction
Here is a sample terminal chat app built with the Kotlin API:

import com.google.ai.edge.litertlm.*

suspend fun main() {
Engine.setNativeMinLogSeverity(LogSeverity.ERROR) // Hide log for TUI app

    val engineConfig = EngineConfig(modelPath = "/path/to/model.litertlm")
    Engine(engineConfig).use { engine ->
        engine.initialize()

        engine.createConversation().use { conversation ->
            while (true) {
                print("\n>>> ")
                conversation.sendMessageAsync(readln()).collect { print(it) }
            }
        }
    }
}



To try out the above sample, clone the repo and run with
example/Main.kt:

bazel run -c opt //kotlin/java/com/google/ai/edge/litertlm/example:main -- <abs_model_path>

Available .litertlm models are on the
HuggingFace LiteRT Community. The
above animation was using the
Gemma3-1B-IT.

For Android sample, check out the
Google AI Edge Gallery app.

Getting Started with Gradle
While LiteRT-LM is developed with Bazel, we provide the Maven packages for
Gradle/Maven users.

1. Add the Gradle dependency
   dependencies {
   // For Android
   implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

        // For JVM (Linux, MacOS, Windows)
        implementation("com.google.ai.edge.litertlm:litertlm-jvm:latest.release")
}

You can find the available versions on Google Maven in
litertlm-android
and
litertlm-jvm.

latest.release can be used to get the latest release.

2. Initialize the Engine
   The Engine is the entry point to the API. Initialize it with the model path
   and configuration. Remember to close the engine to release resources.

Note: The engine.initialize() method can take a significant amount of time
(e.g., up to 10 seconds) to load the model. It is strongly recommended to call
this on a background thread or coroutine to avoid blocking the UI thread.

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig

val engineConfig = EngineConfig(
modelPath = "/path/to/your/model.litertlm", // Replace with your model path
backend = Backend.CPU(), // Or Backend.GPU() and Backend.NPU("...")
// Optional: Pick a writable dir. This can improve 2nd load time.
// cacheDir = "/tmp/" or context.cacheDir.path (for Android)
)

val engine = Engine(engineConfig)
engine.initialize()
// ... Use the engine to create a conversation ...

// Close the engine when done
engine.close()

On Android, to use the GPU backend, the app needs to request the depending
native libraries explicitly by adding the following to your
AndroidManifest.xml inside the <application> tag:

    <application>
        <uses-native-library android:name="libvndksupport.so" android:required="false"/>
        <uses-native-library android:name="libOpenCL.so" android:required="false"/>
    </application>

To use the NPU backend, you might need to specify the directory containing
the NPU libraries. On Android, if the libraries are bundled with your app, set
it to context.applicationInfo.nativeLibraryDir. See LiteRT-LM
NPU for more details
about the NPU native libraries.

val engineConfig = EngineConfig(
modelPath = modelPath,
backend = Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
)

3. Create a Conversation
   Once the engine is initialized, create a Conversation instance. You can
   provide a ConversationConfig to customize its behavior.

import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig

// Optional: Configure the system instruction, initial messages, sampling
// parameters, etc.
val conversationConfig = ConversationConfig(
systemInstruction = Contents.of("You are a helpful assistant."),
initialMessages = listOf(
Message.user("What is the capital city of the United States?"),
Message.model("Washington, D.C."),
),
samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.8),
)

val conversation = engine.createConversation(conversationConfig)
// Or with default config:
// val conversation = engine.createConversation()

// ... Use the conversation ...

// Close the conversation when done
conversation.close()

Conversation implements AutoCloseable, so you can use the use block for
automatic resource management for one-shot or short-lived conversations:

engine.createConversation(conversationConfig).use { conversation ->
// Interact with the conversation
}

4. Sending Messages
   There are three ways to send messages:

sendMessage(contents, extraContext): Message: Synchronous call that
blocks until the model returns a complete response. This is simpler for
basic request/response interactions.
sendMessageAsync(contents, callback, extraContext): Asynchronous call
for streaming responses. This is better for long-running requests or when
you want to display the response as it's being generated.
sendMessageAsync(contents, extraContext): Flow<Message>: Asynchronous
call that returns a Kotlin Flow for streaming responses. This is the
recommended approach for Coroutine users.
Synchronous Example:

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message

print(conversation.sendMessage("What is the capital of France?"))

Asynchronous Example with callback:

Use sendMessageAsync to send a message to the model and receive responses
through a callback.

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

val callback = object : MessageCallback {
override fun onMessage(message: Message) {
print(message)
}

        override fun onDone() {
                // Streaming completed
        }

        override fun onError(throwable: Throwable) {
                // Error during streaming
        }
}

conversation.sendMessageAsync("What is the capital of France?", callback)

Asynchronous Example with Flow:

Use sendMessageAsync (without the callback arg) to send a message to the model
and receive responses through a Kotlin Flow.

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

// Within a coroutine scope
conversation.sendMessageAsync("What is the capital of France?")
.catch { ... } // Error during streaming
.collect { print(it.toString()) }

5. Multi-Modality
   Note: This only works with models with multi-modality support, e.g., the
   Gemma3n.

Message objects can contain different types of Content, including Text,
ImageBytes, ImageFile, and AudioBytes, AudioFile.

// Initialize the `visionBackend` and/or the `audioBackend`
val engineConfig = EngineConfig(
modelPath = "/path/to/your/model.litertlm", // Replace with your model path
backend = Backend.CPU(), // Or Backend.GPU() or Backend.NPU(...)
visionBackend = Backend.GPU(), // Or Backend.NPU(...)
audioBackend = Backend.CPU(), // Or Backend.NPU(...)
)

// Sends a message with multi-modality.
// See the Content class for other variants.
conversation.sendMessage(Contents.of(
Content.ImageFile("/path/to/image"),
Content.AudioBytes(audioBytes), // ByteArray of the audio
Content.Text("Describe this image and audio."),
))

6. Defining and Using Tools
   Note: This only works with models with tool support, e.g., the
   FunctionGemma.

There are two ways to define tools:

With Kotlin functions (recommended for most cases)
With Open API specification (full control of the tool spec and execution)
Defining Tools with Kotlin Functions
You can define custom Kotlin functions as tools that the model can call to
perform actions or fetch information.

Create a class implementing ToolSet and annotate methods with @Tool and
parameters with @ToolParam.

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam

class SampleToolSet: ToolSet {
@Tool(description = "Get the current weather for a city")
fun getCurrentWeather(
@ToolParam(description = "The city name, e.g., San Francisco") city: String,
@ToolParam(description = "Optional country code, e.g., US") country: String? = null,
@ToolParam(description = "Temperature unit (celsius or fahrenheit). Default: celsius") unit: String = "celsius"
): Map<String, Any> {
// In a real application, you would call a weather API here
return mapOf("temperature" to 25, "unit" to  unit, "condition" to "Sunny")
}

        @Tool(description = "Get the sum of a list of numbers.")
        fun sum(
                @ToolParam(description = "The numbers, could be floating point.") numbers: List<Double>,
        ): Double {
                return numbers.sum()
        }
}

Behind the scenes, the API inspects these annotations and the function signature
to generate an OpenAPI-style schema. This schema describes the tool's
functionality, parameters (including their types and descriptions from
@ToolParam), and return type to the language model.

Parameter Types
The types for parameters annotated with @ToolParam can be String, Int,
Boolean, Float, Double, or a List of these types (e.g., List<String>).
Use nullable types (e.g., String?) to indicate nullable parameters. Set a
default value to indicate that the parameter is optional, and mention the
default value in the description in @ToolParam.

Return Type
The return type of your tool function can be any Kotlin type. The result will be
converted to a JSON element before being sent back to the model.

List types are converted to JSON arrays.
Map types are converted to JSON objects.
Primitive types (String, Number, Boolean) are converted to the
corresponding JSON primitive.
Other types are converted to strings with the toString() method.
For structured data, returning Map or a data class that will be converted to a
JSON object is recommended.

Defining Tools with OpenAPI Specification
Alternatively, you can define a tool by implementing the OpenApiTool class and
providing the tool's description as a JSON string conforming to the Open API
specification. This method is useful if you already have an OpenAPI schema for
your tool or if you need fine-grained control over the tool's definition.

import com.google.ai.edge.litertlm.OpenApiTool

class SampleOpenApiTool : OpenApiTool {

        override fun getToolDescriptionJsonString(): String {
                return """
                {
                    "name": "addition",
                    "description": "Add all numbers.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "numbers": {
                                "type": "array",
                                "items": {
                                    "type": "number"
                                }
                            },
                            "description": "The list of numbers to sum."
                        },
                        "required": [
                            "numbers"
                        ]
                    }
                }
                """.trimIndent() // Tip: trim to save tokens
        }

        override fun execute(paramsJsonString: String): String {
                // Parse paramsJsonString with your choice of parser/deserializer and
                // execute the tool.

                // Return the result as a JSON string
                return """{"result": 1.4142}"""
        }
}

Registering Tools
Include instances of your tools in the ConversationConfig.

val conversation = engine.createConversation(
ConversationConfig(
tools = listOf(
tool(SampleToolSet()),
tool(SampleOpenApiTool()),
),
// ... other configs
)
)

// Send messages that might trigger the tool
conversation.sendMessageAsync("What's the weather like in London?", callback)

The model will decide when to call the tool based on the conversation. The
results from the tool execution are automatically sent back to the model to
generate the final response.

Manual Tool Calling
By default, tool calls generated by the model are automatically executed by
LiteRT-LM and the results from the tool execution are automatically sent back to
the model to generate the next response.

If you want to manually execute tools and send results back to the model, you
can set automaticToolCalling in ConversationConfig to false.

val conversation = engine.createConversation(
ConversationConfig(
tools = listOf(
tool(SampleOpenApiTool()),
),
automaticToolCalling = false,
)
)

If you disable automatic tool calling, you will need to manually execute tools
and send results back to the model in your application code. The execute
method of OpenApiTool will not be called automatically when
automaticToolCalling is set to false.

// Send a message that triggers a tool call.
val responseMessage = conversation.sendMessage("What's the weather like in London?")

// The model returns a Message with `toolCalls` populated.
if (responseMessage.toolCalls.isNotEmpty()) {
val toolResponses = mutableListOf<Content.ToolResponse>()
// There can be multiple tool calls in a single response.
for (toolCall in responseMessage.toolCalls) {
println("Model wants to call: ${toolCall.name} with arguments: ${toolCall.arguments}")

                // Execute the tool manually with your own logic. `executeTool` is just an example here.
                val toolResponseJson = executeTool(toolCall.name, toolCall.arguments)

                // Collect tool responses.
                toolResponses.add(Content.ToolResponse(toolCall.name, toolResponseJson))
        }

        // Use Message.tool to create the tool response message.
        val toolResponseMessage = Message.tool(Contents.of(toolResponses))

        // Send the tool response message to the model.
        val finalMessage = conversation.sendMessage(toolResponseMessage)
        println("Final answer: ${finalMessage.text}") // e.g., "The weather in London is 25c."
}

Example
To try out tool use, clone the repo and run with
example/ToolMain.kt:

bazel run -c opt //kotlin/java/com/google/ai/edge/litertlm/example:tool -- <abs_model_path>

7. Extra Template Context Variables
   You can pass extra context variables to the prompt template for rendering.
   This allows you to customize the model's behavior based on dynamic values.

extraContext is an optional Map<String, Any> that can be passed to
sendMessage and sendMessageAsync. These variables are merged with the extra
context provided in the Preface (if any), with keys in the message-level
context overwriting those in the Preface.

val extraContext = mapOf(
"user_name" to "Alice",
"enable_thinking" to true
)

// Synchronous
val response = conversation.sendMessage("Hello!", extraContext = extraContext)

// Asynchronous with Flow
conversation.sendMessageAsync("Hello!", extraContext = extraContext)
.collect { ... }

These variables are used within the Jinja-style prompt templates, e.g.,
{{ user_name }} or {% if enable_thinking %}.

Error Handling
API methods can throw LiteRtLmJniException for errors from the native layer or
standard Kotlin exceptions like IllegalStateException for lifecycle issues.
Always wrap API calls in try-catch blocks. The onError callback in
MessageCallback will also report errors during asynchronous operations.


Code example from Google AI Edge app for Audio Scribe

*

Copyright 2025 Google LLC
Licensed under the Apache License, Version 2.0 (the "License");
*you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
*distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*See the License for the specific language governing permissions and
limitations under the License.
*/
package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.common.cleanUpMediapipeTaskErrorMessage
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.DEFAULT_VISION_ACCELERATOR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.THOUGHT_CHANNEL
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AGLlmChatModelHelper"

data class LlmModelInstance(val engine: Engine, var conversation: Conversation)

object LlmChatModelHelper : LlmModelHelper {
// Indexed by model name.
private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

@OptIn(ExperimentalApi::class) // opt-in experimental flags
override fun initialize(
context: Context,
model: Model,
taskId: String,
supportImage: Boolean,
supportAudio: Boolean,
onDone: (String) -> Unit,
systemInstruction: Contents?,
tools: List<ToolProvider>,
enableConversationConstrainedDecoding: Boolean,
coroutineScope: CoroutineScope?,
) {
if (model.instance != null) {
Log.d(TAG, "Model '${model.name}' already initialized in LlmChatModelHelper. Skipping.")
model.markInitialized()
onDone("")
return
}
model.markInitializationStarted()
// Prepare options.
val maxTokens =
model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
val temperature =
model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
val accelerator =
model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
val visionAccelerator =
model.getStringConfigValue(
key = ConfigKeys.VISION_ACCELERATOR,
defaultValue = DEFAULT_VISION_ACCELERATOR.label,
)
val visionBackend =
when (visionAccelerator) {
Accelerator.CPU.label -> Backend.CPU()
Accelerator.GPU.label -> Backend.GPU()
Accelerator.NPU.label ->
Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
Accelerator.TPU.label ->
Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
else -> Backend.GPU()
}
val shouldEnableImage = supportImage
val shouldEnableAudio = supportAudio
val preferredBackend =
when (accelerator) {
Accelerator.CPU.label -> Backend.CPU()
Accelerator.GPU.label -> Backend.GPU()
Accelerator.NPU.label ->
Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
Accelerator.TPU.label ->
Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
else -> Backend.CPU()
}
Log.d(TAG, "Preferred backend: $preferredBackend")

    val modelPath = model.getPath(context = context)
    val engineConfig =
        EngineConfig(
            modelPath = modelPath,
            backend = preferredBackend,
            visionBackend = if (shouldEnableImage) visionBackend else null, // must be GPU for Gemma 3n
            audioBackend = if (shouldEnableAudio) Backend.CPU() else null, // must be CPU for Gemma 3n
            maxNumTokens = maxTokens,
            cacheDir =
                if (modelPath.startsWith("/data/local/tmp"))
                    context.getExternalFilesDir(null)?.absolutePath
                else null,
        )

    // Check if the model file supports speculative decoding.
    var supportsSpeculativeDecoding = false
    // Check if the model file supports speculative decoding.
    try {
        com.google.ai.edge.litertlm.Capabilities(modelPath).use {
            supportsSpeculativeDecoding = it.hasSpeculativeDecodingSupport()
        }
    } catch (e: Exception) {
        // Ignore exceptions and assume not supported.
    }
    // Create an instance of LiteRT LM engine and conversation.
    try {
        var speculativeDecoding = false
        // Check if the model supports speculative decoding for the given task type and if the
        // speculative decoding is enabled in the settings.
        if (
            supportsSpeculativeDecoding &&
                model.capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING]?.contains(taskId) ==
                    true
        ) {
            speculativeDecoding =
                model.getBooleanConfigValue(
                    key = ConfigKeys.ENABLE_SPECULATIVE_DECODING,
                    defaultValue = false,
                )
        }
        ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
        Log.d(TAG, "Speculative decoding enabled: $speculativeDecoding")
        val engine = Engine(engineConfig)
        engine.initialize()
        ExperimentalFlags.enableSpeculativeDecoding = false

        ExperimentalFlags.enableConversationConstrainedDecoding =
            enableConversationConstrainedDecoding
        val conversation =
            engine.createConversation(
                ConversationConfig(
                    samplerConfig =
                        if (preferredBackend is Backend.NPU) {
                            null
                        } else {
                            SamplerConfig(
                                topK = topK,
                                topP = topP.toDouble(),
                                temperature = temperature.toDouble(),
                            )
                        },
                    systemInstruction = systemInstruction,
                    tools = tools,
                )
            )
        ExperimentalFlags.enableConversationConstrainedDecoding = false
        model.instance = LlmModelInstance(engine = engine, conversation = conversation)
    } catch (e: Exception) {
        val errorMsg = cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error")
        model.markInitializationFailed(errorMsg)
        onDone(errorMsg)
        return
    }
    model.markInitialized()
    onDone("")
}

@OptIn(ExperimentalApi::class) // opt-in experimental flags
override fun resetConversation(
model: Model,
supportImage: Boolean,
supportAudio: Boolean,
systemInstruction: Contents?,
tools: List<ToolProvider>,
enableConversationConstrainedDecoding: Boolean,
initialMessages: List<Message>,
) {
try {
Log.d(TAG, "Resetting conversation for model '${model.name}'")

        val instance = model.instance as LlmModelInstance? ?: return
        instance.conversation.close()

        val engine = instance.engine
        val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
        val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
        val temperature =
            model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
        val shouldEnableImage = supportImage
        val shouldEnableAudio = supportAudio
        Log.d(TAG, "Enable image: $shouldEnableImage, enable audio: $shouldEnableAudio")

        val accelerator =
            model.getStringConfigValue(
                key = ConfigKeys.ACCELERATOR,
                defaultValue = Accelerator.GPU.label,
            )
        ExperimentalFlags.enableConversationConstrainedDecoding =
            enableConversationConstrainedDecoding
        val newConversation =
            engine.createConversation(
                ConversationConfig(
                    samplerConfig =
                        if (accelerator == Accelerator.NPU.label || accelerator == Accelerator.TPU.label) {
                            null
                        } else {
                            SamplerConfig(
                                topK = topK,
                                topP = topP.toDouble(),
                                temperature = temperature.toDouble(),
                            )
                        },
                    systemInstruction = systemInstruction,
                    tools = tools,
                    initialMessages = initialMessages,
                )
            )
        ExperimentalFlags.enableConversationConstrainedDecoding = false
        instance.conversation = newConversation

        Log.d(TAG, "Resetting done")
    } catch (e: Exception) {
        Log.d(TAG, "Failed to reset conversation", e)
    }
}

override fun cleanUp(model: Model, onDone: () -> Unit) {
if (model.instance == null) {
return
}

    val instance = model.instance as LlmModelInstance

    try {
        instance.conversation.close()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to close the conversation: ${e.message}")
    }

    try {
        instance.engine.close()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to close the engine: ${e.message}")
    }

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
        onCleanUp()
    }
    model.resetInitialization()

    onDone()
    Log.d(TAG, "Clean up done.")
}

override fun stopResponse(model: Model) {
val instance = model.instance as? LlmModelInstance ?: return
try {
instance.conversation.cancelProcess()
} catch (e: IllegalStateException) {
Log.w(TAG, "Conversation is not alive, cannot cancel process", e)
}
}

override fun runInference(
model: Model,
input: String,
resultListener: ResultListener,
cleanUpListener: CleanUpListener,
onError: (message: String) -> Unit,
images: List<Bitmap>,
audioClips: List<ByteArray>,
coroutineScope: CoroutineScope?,
extraContext: Map<String, String>?,
) {
val instance = model.instance as? LlmModelInstance
if (instance == null) {
onError("LlmModelInstance is not initialized.")
return
}

    // Set listener.
    if (!cleanUpListeners.containsKey(model.name)) {
        cleanUpListeners[model.name] = cleanUpListener
    }

    val conversation = instance.conversation

    val contents = mutableListOf<Content>()
    for (image in images) {
        contents.add(Content.ImageBytes(image.toPngByteArray()))
    }
    for (audioClip in audioClips) {
        contents.add(Content.AudioBytes(audioClip))
    }
    // add the text after image and audio for the accurate last token
    if (input.trim().isNotEmpty()) {
        contents.add(Content.Text(input))
    }

    conversation.sendMessageAsync(
        Contents.of(contents),
        object : MessageCallback {
            override fun onMessage(message: Message) {
                resultListener(message.toString(), false, message.channels[THOUGHT_CHANNEL])
            }

            override fun onDone() {
                resultListener("", true, null)
            }

            override fun onError(throwable: Throwable) {
                if (throwable is CancellationException) {
                    Log.i(TAG, "The inference is cancelled.")
                    resultListener("", true, null)
                } else {
                    Log.e(TAG, "onError", throwable)
                    onError("Error: ${throwable.message}")
                }
            }
        },
        extraContext ?: emptyMap(),
    )
}

private fun Bitmap.toPngByteArray(): ByteArray {
val stream = ByteArrayOutputStream()
this.compress(Bitmap.CompressFormat.PNG, 100, stream)
return stream.toByteArray()
}

}