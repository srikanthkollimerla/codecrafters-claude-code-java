import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.FunctionDefinition;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.core.JsonValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Map;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        if (args.length < 2 || !"-p".equals(args[0])) {
            System.err.println("Usage: program -p <prompt>");
            System.exit(1);
        }

        String prompt = args[1];

        String apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }

        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OPENROUTER_API_KEY is not set");
        }


        //creating a FunctionDefinition for the read tool
        FunctionDefinition readTool = FunctionDefinition.builder()
        .name("Read")
        .description("Read and return the contents of a file")
        .parameters(JsonValue.from(Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of(
                    "type", "string",
                    "description", "The path to the file to read"
                )
            ),
            "required", List.of("file_path")
        )))
        .build();

        //creating a functiondefinition for the write tool
        FunctionDefinition writeTool = FunctionDefinition.builder()
        .name("Write")
        .description("Write to a file")
        .parameters(JsonValue.from(Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of(
                    "type", "string",
                    "description", "The path to the file to write to"
                ),
                "content", Map.of(
                    "type", "string",
                    "description", "The content to write to the file"
                )
            ),
            "required", List.of("file_path", "content")
        )))
        .build();

        //creating a functiondefinition for the bash tool
        FunctionDefinition bashTool = FunctionDefinition.builder()
        .name("Bash")
        .description("Execute a shell command")
        .parameters(JsonValue.from(Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of(
                    "type", "string",
                    "description", "The command to execute"
                )
            ),
            "required", List.of("command")
        )))
        .build();

        //pass readTool, bashTool, and  writeTool to ChatCompletionTool builder
        ChatCompletionTool readToolDefinition = ChatCompletionTool.builder()
        .function(readTool)
        .build();

        ChatCompletionTool writeToolDefinition = ChatCompletionTool.builder()
        .function(writeTool)
        .build();

        ChatCompletionTool bashToolDefinition = ChatCompletionTool.builder()
        .function(bashTool)
        .build();

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder().content(prompt).build()
        ));

        while(true) {
            ChatCompletion response = client.chat().completions().create(
                    ChatCompletionCreateParams.builder()
                            .model("anthropic/claude-haiku-4.5")
                            .messages(messages)
                            .tools(List.of(readToolDefinition, writeToolDefinition, bashToolDefinition))
                            .build()
            );

            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            // You can use print statements as follows for debugging, they'll be visible when running tests.
            System.err.println("Logs from your program will appear here!");

            ChatCompletionMessage message = response.choices().get(0).message();

            // 1. Record the assistant's response to the chat history
            ChatCompletionAssistantMessageParam.Builder assistantBuilder = ChatCompletionAssistantMessageParam.builder();
            if (message.content().isPresent()) {
                assistantBuilder.content(message.content().get());
            }
            if (message.toolCalls().isPresent() && !message.toolCalls().get().isEmpty()) {
                assistantBuilder.toolCalls(message.toolCalls().get());
            }
            messages.add(ChatCompletionMessageParam.ofAssistant(assistantBuilder.build()));

            if (message.toolCalls().isPresent() && !message.toolCalls().get().isEmpty()) {
                // 2. Execute each requested tool
                for (ChatCompletionMessageToolCall toolCall : message.toolCalls().get()) {
                    String toolName = toolCall.function().name();
                    String argumentsString = toolCall.function().arguments();
                    JSONObject argsObj = new JSONObject(argumentsString);
                    
                    String toolResult;
                    try {
                        if ("Read".equals(toolName)) {
                            String filePath = argsObj.getString("file_path");
                            toolResult = Files.readString(Path.of(filePath));
                        } else if ("Write".equals(toolName)) {
                            String filePath = argsObj.getString("file_path");
                            String content = argsObj.getString("content");
                            Files.writeString(Path.of(filePath), content);
                            toolResult = "File successfully written.";
                        } else if("Bash".equals(toolName)) {
                            String command = argsObj.getString("command");
                            ProcessBuilder processBuilder = new ProcessBuilder("sh", "-c", command);
                            processBuilder.redirectErrorStream(true); // Merges standard error into standard output
                            Process process = processBuilder.start();
                            toolResult = new String(process.getInputStream().readAllBytes());
                            process.waitFor();
                        } else {
                            toolResult = "Error: Unknown tool " + toolName;
                        }
                    } catch (Exception e) {
                        toolResult = "Error: " + e.getMessage();
                    }
                    
                    // 3. Add each tool call result back to the messages array
                    messages.add(ChatCompletionMessageParam.ofTool(
                            ChatCompletionToolMessageParam.builder()
                                    .toolCallId(toolCall.id())
                                    .content(toolResult)
                                    .build()
                    ));
                }
            } else {
                // 4. Repeat until complete (no tool calls present)
                System.out.print(message.content().orElse(""));
                break;
            }
        }
    }
}
