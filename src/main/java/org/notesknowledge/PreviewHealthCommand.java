package org.notesknowledge;

/** Container health probe; fixed loopback destination, no secret or response logging. */
public final class PreviewHealthCommand {
    private PreviewHealthCommand() {}
    public static void main(String[] arguments) {
        try {
            var client=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:8081/actuator/health/readiness"))
                .timeout(java.time.Duration.ofSeconds(3)).GET().build();
            if(client.send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()==200) return;
        } catch(Exception unavailable) { /* Status only. */ }
        System.exit(1);
    }
}
