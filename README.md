# Whisper Lounge — student blog

A simple English-language blog where students can share stories about themselves, their dreams, and their favorite things. Built by Whisper. The server is written in Java and does not require external libraries or a database server.

## Requirements

- Java 17 or newer

## Run on Windows

Open a terminal in the project folder and run:

```powershell
New-Item -ItemType Directory -Force out | Out-Null
javac --add-modules jdk.httpserver -d out src\main\java\BlogServer.java
java --add-modules jdk.httpserver -cp out BlogServer
```

Open [http://localhost:8080](http://localhost:8080) in your browser. To use a different port, set the `PORT` environment variable first.

## Features

- A form for the author’s name, title, topic, story, and optional dream.
- Validation for required fields, input length, and topic.
- User content is escaped before being displayed as HTML.
- Posts persist after the server restarts and are saved as readable text in `data\posts.txt`, which opens as a normal text file. Tabs and line breaks are escaped so each post stays on one line. Existing `data\posts.tsv` files are automatically renamed when the server starts.
- Responsive layout for phones and desktops.

Posts are publicly visible on this page. To remove them, stop the server and delete `data\posts.txt`.
