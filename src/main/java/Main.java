import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
// The explicit correct import provided by the compiler hint
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.core.JsonValue;
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

        // 1. Setup the parameters payload using a standard map
        Map<String, Object> innerProperties = Map.of(
            "file_path", Map.of(
                "type", "string",
                "description", "The path to the file to read"
            )
        );

        Map<String, Object> parametersMap = Map.of(
            "type", "object",
            "properties", innerProperties,
            "required", List.of("file_path")
        );

        // 2. Build the tool using ChatCompletionTool.builder() exactly as requested
        ChatCompletionTool readTool = ChatCompletionTool.builder()
                .type(ChatCompletionTool.Type.FUNCTION)
                .function(ChatCompletionTool.Function.builder()
                        .name("Read")
                        .description("Read and return the contents of a file")
                        .parameters(JsonValue.from(parametersMap))
                        .build())
                .build();

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        // 3. Construct the completion parameters and add the tool
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model("anthropic/claude-haiku-4.5")
                .addUserMessage(prompt)
                .addTool(readTool)
                .build();

        ChatCompletion response = client.chat().completions().create(params);

        if (response.choices().isEmpty()) {
            throw new RuntimeException("no choices in response");
        }

        System.err.println("Logs from your program will appear here!");

        System.out.print(response.choices().get(0).message().content().orElse(""));
    }
}