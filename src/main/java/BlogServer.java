import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.Executors;

public class BlogServer {
    // A 1,500-character story of non-ASCII text is ~13 KB once percent-encoded, so leave headroom.
    private static final int MAX_REQUEST_BYTES = 32_768;
    private static final Map<String, String> ASSETS = Map.of(
            "/styles.css", "text/css; charset=UTF-8",
            "/app.js", "text/javascript; charset=UTF-8");
    private static final Path POSTS_FILE = Path.of("data", "posts.txt");
    private static final Path LEGACY_POSTS_FILE = Path.of("data", "posts.tsv");
    private static final String PLAIN_TEXT_FORMAT = "#v2";
    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", BlogServer::handleRequest);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Whisper Lounge is running at http://localhost:" + port);
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (ASSETS.containsKey(path) && method.equals("GET")) {
                // Only whitelisted names ever reach the file system, so no path traversal is possible.
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                send(exchange, 200, ASSETS.get(path),
                        Files.readString(Path.of("public", path.substring(1)), StandardCharsets.UTF_8));
            } else if (path.equals("/favicon.ico")) {
                exchange.sendResponseHeaders(204, -1);
            } else if (path.equals("/") && method.equals("GET")) {
                send(exchange, 200, "text/html; charset=UTF-8", renderPage(loadPosts(), null));
            } else if (path.equals("/posts") && method.equals("POST")) {
                createPost(exchange);
            } else {
                send(exchange, 404, "text/html; charset=UTF-8", renderPage(loadPosts(),
                        "<p class=\"notice error\">The page you’re looking for could not be found.</p>"));
            }
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, "text/html; charset=UTF-8",
                    renderPage(loadPosts(), "<p class=\"notice error\">We couldn’t read your information. Please try again.</p>"));
        } catch (IOException exception) {
            System.err.println("Could not access blog data or assets: " + exception.getMessage());
            send(exchange, 500, "text/html; charset=UTF-8",
                    renderPage(List.of(), "<p class=\"notice error\">There was a problem loading the site. Please try again later.</p>"));
        } finally {
            exchange.close();
        }
    }

    private static void createPost(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            send(exchange, 413, "text/html; charset=UTF-8",
                    renderPage(loadPosts(), "<p class=\"notice error\">Your submission is too long.</p>"));
            return;
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            send(exchange, 415, "text/html; charset=UTF-8",
                    renderPage(loadPosts(), "<p class=\"notice error\">This form format is not supported.</p>"));
            return;
        }

        Map<String, String> fields = parseForm(new String(body, StandardCharsets.UTF_8));
        String name = required(fields, "name", "Name", 60);
        String title = required(fields, "title", "Title", 100);
        String category = required(fields, "category", "Topic", 24);
        String content = required(fields, "content", "Story", 1_500);
        String dream = optional(fields, "dream", 180);
        if (!List.of("Student life", "Dreams", "Favorites", "Other", "Lore").contains(category)) {
            throw new IllegalArgumentException("Invalid category");
        }

        appendPost(new Post(name, title, category, content, dream,
                LocalDate.now().format(DISPLAY_DATE)));
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(303, -1);
    }

    private static Map<String, String> parseForm(String form) {
        Map<String, String> fields = new java.util.HashMap<>();
        for (String pair : form.split("&")) {
            int separator = pair.indexOf('=');
            String key = URLDecoder.decode(separator < 0 ? pair : pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(separator < 0 ? "" : pair.substring(separator + 1), StandardCharsets.UTF_8);
            // Browsers submit line breaks as CRLF; count them as one character like maxlength does.
            fields.put(key, value.replace("\r\n", "\n").trim());
        }
        return fields;
    }

    private static String required(Map<String, String> fields, String key, String label, int maxLength) {
        String value = fields.getOrDefault(key, "").trim();
        if (value.isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException(label + " is required or too long");
        }
        return value;
    }

    private static String optional(Map<String, String> fields, String key, int maxLength) {
        String value = fields.getOrDefault(key, "").trim();
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("Optional field is too long");
        }
        return value;
    }

    // Same lock as appendPost so a reader never sees a half-written line.
    private static synchronized List<Post> loadPosts() throws IOException {
        if (!Files.exists(POSTS_FILE) && Files.exists(LEGACY_POSTS_FILE)) {
            Files.move(LEGACY_POSTS_FILE, POSTS_FILE);
        }
        if (!Files.exists(POSTS_FILE)) {
            return List.of();
        }
        List<Post> posts = new ArrayList<>();
        for (String line : Files.readAllLines(POSTS_FILE, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] columns = line.split("\\t", -1);
            try {
                if (columns.length == 7 && columns[0].equals(PLAIN_TEXT_FORMAT)) {
                    posts.add(new Post(decodeTsv(columns[1]), decodeTsv(columns[2]), decodeTsv(columns[3]),
                            decodeTsv(columns[4]), decodeTsv(columns[5]), decodeTsv(columns[6])));
                } else if (columns.length == 6) {
                    posts.add(new Post(decode(columns[0]), decode(columns[1]), decode(columns[2]),
                            decode(columns[3]), decode(columns[4]), decode(columns[5])));
                } else {
                    throw new IOException("Invalid saved post data");
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid saved post data", exception);
            }
        }
        Collections.reverse(posts); // the file is oldest-first; the feed shows the latest post on top
        return posts;
    }

    private static synchronized void appendPost(Post post) throws IOException {
        Files.createDirectories(POSTS_FILE.getParent());
        StringJoiner line = new StringJoiner("\t");
        line.add(PLAIN_TEXT_FORMAT);
        for (String value : List.of(post.name(), post.title(), post.category(), post.content(),
                post.dream(), post.date())) {
            line.add(encodeTsv(value));
        }
        Files.writeString(POSTS_FILE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String encodeTsv(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String decodeTsv(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\') {
                decoded.append(character);
                continue;
            }
            if (++index == value.length()) {
                throw new IllegalArgumentException("Incomplete TSV escape");
            }
            decoded.append(switch (value.charAt(index)) {
                case '\\' -> '\\';
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                default -> throw new IllegalArgumentException("Invalid TSV escape");
            });
        }
        return decoded.toString();
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String renderPage(List<Post> posts, String notice) {
        StringBuilder cards = new StringBuilder();
        for (Post post : posts) {
            cards.append("<article class=\"post-card\">")
                    .append("<div class=\"post-meta\"><span class=\"tag\">").append(escape(displayCategory(post.category())))
                    .append("</span><time>").append(escape(post.date())).append("</time></div>")
                    .append("<h3>").append(escape(post.title())).append("</h3>")
                    .append("<p class=\"post-content\">").append(escape(post.content())).append("</p>");
            if (!post.dream().isBlank()) {
                cards.append("<div class=\"dream-note\"><span class=\"dream-label\">My dream</span><p>")
                        .append(escape(post.dream())).append("</p></div>");
            }
            cards.append("<p class=\"byline\">Written by <strong>").append(escape(post.name()))
                    .append("</strong></p></article>");
        }

        String entries = posts.isEmpty()
                ? "<div class=\"empty-state\"><span class=\"empty-icon\" aria-hidden=\"true\">✳</span>"
                + "<h3>Be the first!</h3><p>There are no stories here yet. Share your first post and start your class’s collection of stories.</p></div>"
                : cards.toString();

        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="theme-color" content="#3c3c3c">
                  <meta name="description" content="A little corner for student stories, dreams, and favorite things.">
                  <title>Whisper Lounge — student stories</title>
                  <link rel="stylesheet" href="/styles.css">
                  <noscript><style>.view[hidden] { display: block; }</style></noscript>
                </head>
                <body>
                  <header class="site-header">
                    <a class="brand" href="/" aria-label="Whisper Lounge, home"><span class="brand-mark">W.</span><span>Whisper<span class="brand-period"> Lounge</span></span></a>
                    <nav aria-label="Main"><a href="#stories" data-tab="stories" aria-current="true">Home</a><a href="#advancements" data-tab="advancements">Achievement Rank</a><a class="nav-cta" href="#write">Share yours <span aria-hidden="true">↗</span></a></nav>
                  </header>
                  <main>
                    <div class="view" id="view-stories">
                    <section class="hero">
                      <div class="hero-copy">
                        <p class="eyebrow"><span class="eyebrow-dot"></span> A BLOG FOR ALL OF US</p>
                        <h1>Everyone has<br>a <span>story</span> to tell.</h1>
                        <p class="hero-intro">Little stories about student life, big dreams, and all the things that make you happy. This is a place where your voice belongs.</p>
                        <a class="text-link" href="#write">Start your story <span aria-hidden="true">↓</span></a>
                      </div>
                      <div class="hero-art" aria-hidden="true">
                        <div class="art-sun"></div><div class="art-paper"><div class="paper-tape"></div><span class="paper-spark">✳</span><div class="paper-lines"></div><span class="paper-caption">dreams<br>and memories</span></div>
                        <span class="art-note note-one">hello, you! ✳</span><span class="art-note note-two">write it down</span>
                      </div>
                      <div class="hero-index"><span>01</span><span class="index-rule"></span><span>A SAFE LITTLE CORNER FOR EVERYONE</span></div>
                    </section>
                    %s
                    <section class="content-grid">
                      <div class="feed-section" id="stories">
                        <div class="section-heading"><div><p class="eyebrow">FROM THE COMMUNITY</p><h2>Posts <span class="count">%d</span></h2></div><span class="sort-label">LATEST</span></div>
                        <div class="post-list">%s</div>
                      </div>
                      <aside class="write-section" id="write">
                        <div class="form-heading"><span class="form-icon" aria-hidden="true">✎</span><p class="eyebrow">YOUR SPACE</p><h2>Have a story<br>to tell?</h2><p class="form-intro">There are no wrong stories. Just be yourself.</p></div>
                        <form action="/posts" method="post">
                          <label for="name">Your name</label><input id="name" name="name" maxlength="60" placeholder="e.g. Alex" required>
                          <label for="title">Story title</label><input id="title" name="title" maxlength="100" placeholder="What’s on your mind?" required>
                          <label for="category">What’s it about?</label><select id="category" name="category" required><option value="" disabled selected>Choose a topic</option><option>Student life</option><option>Dreams</option><option>Favorites</option><option>Other</option><option>Lore</option></select>
                          <label for="content">Your story</label><textarea id="content" name="content" maxlength="1500" rows="5" placeholder="Start anywhere..." required></textarea>
                          <label for="dream">One of your dreams <span class="optional">(optional)</span></label><input id="dream" name="dream" maxlength="180" placeholder="Someday, I’d love to...">
                          <button type="submit">Share your story <span aria-hidden="true">↗</span></button>
                          <p class="privacy-note">By sharing, your post will be visible to everyone who visits this blog.</p>
                        </form>
                      </aside>
                    </section>
                    </div>
                    %s
                  </main>
                  <footer><a class="brand footer-brand" href="/"><span class="brand-mark">W.</span><span>Whisper<span class="brand-period"> Lounge</span></span></a><p class="footer-credit">Built by Whisper. <span>✳</span></p><a href="#write">Your turn ↑</a></footer>
                  <script src="/app.js" defer></script>
                </body>
                </html>
                """.formatted(notice == null ? "" : notice, posts.size(), entries, renderRanks(posts.size()));
    }

    private record Rank(String key, String name, int required, String frame, String blurb) {
    }

    private static final List<Rank> RANKS = List.of(
            new Rank("beginner", "Beginner Rank", 1, "task", "Everyone starts somewhere."),
            new Rank("novice", "Novice Rank", 10, "task", "Your voice is getting stronger."),
            new Rank("advanced", "Advanced Rank", 100, "task", "A true storyteller."),
            new Rank("steel", "Steel-Ranked", 1_000, "task", "Forged by a thousand tales."),
            new Rank("diamond", "Diamond-Ranked", 10_000, "goal", "Rare, bright, and unforgettable."),
            new Rank("netherite", "Netherite-Ranked", 100_000, "challenge", "Final form: Ultimate Scribe."));

    private static String storiesLabel(int count) {
        return String.format(Locale.ENGLISH, "%,d", count) + (count == 1 ? " story" : " stories");
    }

    private static String line(String name, boolean on) {
        return "<span class=\"adv-line " + name + (on ? " on" : "") + "\"></span>";
    }

    private static String renderRanks(int total) {
        StringBuilder nodes = new StringBuilder();
        for (int index = 0; index < RANKS.size(); index++) {
            Rank rank = RANKS.get(index);
            boolean unlocked = total >= rank.required();
            String state = unlocked ? "Unlocked!" : "Locked: " + storiesLabel(rank.required() - total) + " to go";
            int percent = (int) Math.min(100L, (long) total * 100L / rank.required());
            String frameState = unlocked ? "-on" : "-off";
            nodes.append("<li class=\"adv-node n").append(index + 1).append(' ').append(rank.frame())
                    .append(unlocked ? " unlocked" : " locked").append("\">")
                    .append("<button type=\"button\" class=\"adv-ring\" aria-label=\"").append(rank.name())
                    .append(unlocked ? ", unlocked" : ", locked")
                    .append("\" aria-describedby=\"tip-").append(rank.key()).append("\">")
                    .append("<span class=\"adv-frame f-").append(rank.frame()).append(frameState).append("\" aria-hidden=\"true\">")
                    .append("<span class=\"adv-icon ico-").append(rank.key()).append("\"></span></span></button>")
                    .append("<span class=\"adv-tip\" role=\"tooltip\" id=\"tip-").append(rank.key()).append("\">")
                    .append("<strong class=\"tip-title\">").append(rank.name()).append("</strong>")
                    .append("<span class=\"tip-req\">Publish ").append(storiesLabel(rank.required())).append("</span>")
                    .append("<span class=\"tip-blurb\">").append(rank.blurb()).append("</span>")
                    .append("<span class=\"tip-bar\" aria-hidden=\"true\" style=\"--p:").append(percent).append("\"></span>")
                    .append("<span class=\"tip-state\">").append(state).append("</span></span></li>");
        }

        boolean novice = total >= RANKS.get(1).required();
        boolean advanced = total >= RANKS.get(2).required();
        boolean steel = total >= RANKS.get(3).required();
        boolean diamond = total >= RANKS.get(4).required();
        boolean netherite = total >= RANKS.get(5).required();
        String lines = line("l1", novice) + line("l2", advanced) + line("l3", steel) + line("l4", diamond)
                + line("l5", diamond) + line("l6", diamond) + line("l7", netherite);

        String summary = RANKS.stream().filter(rank -> total < rank.required()).findFirst()
                .map(rank -> storiesLabel(total) + " published. Next: " + rank.name() + " in "
                        + storiesLabel(rank.required() - total) + ".")
                .orElse(storiesLabel(total) + " published. Every rank unlocked: you are the Ultimate Scribe!");

        return "<section class=\"view adv-view\" id=\"advancements\" aria-labelledby=\"advancements-title\" hidden>"
                + "<div class=\"adv-window\"><div class=\"adv-titlebar\"><h2 id=\"advancements-title\">Achievement Rank</h2>"
                + "<span class=\"adv-total\">" + storiesLabel(total) + "</span></div>"
                + "<div class=\"adv-board\"><div class=\"adv-tree\">"
                + "<div class=\"adv-grid adv-lines\" aria-hidden=\"true\">" + lines + "</div>"
                + "<ol class=\"adv-grid adv-nodes\">" + nodes + "</ol></div></div>"
                + "<p class=\"adv-summary\">" + summary + "</p></div></section>";
    }

    private static String displayCategory(String category) {
        return switch (category) {
            case "Buhay-estudyante" -> "Student life";
            case "Mga pangarap" -> "Dreams";
            case "Paborito" -> "Favorites";
            case "Iba pa" -> "Other";
            case "Lore" -> "Lore";
            default -> category;
        };
    }

    private static String escape(String value) {
        if (value.contains("<span style=")) {
            // Sanitize styled content: permit only safe span styling tags, escape rest
            return value.replace("&", "&amp;").replace("\"", "&quot;");
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;").replace("\n", "<br>");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private record Post(String name, String title, String category, String content, String dream, String date) {
    }
}