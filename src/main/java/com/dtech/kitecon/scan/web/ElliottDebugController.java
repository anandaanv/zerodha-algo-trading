package com.dtech.kitecon.scan.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/debug/elliott")
@Slf4j
public class ElliottDebugController {

    private static final File DUMP_DIR = new File("/tmp/elliott-debug");
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> listDumps() {
        List<Map<String, Object>> result = new ArrayList<>();
        File[] files = DUMP_DIR.exists() ? DUMP_DIR.listFiles((d, n) -> n.endsWith(".json")) : null;
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (File f : files) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("filename", f.getName());
                entry.put("size", f.length());
                entry.put("modified", FMT.format(Instant.ofEpochMilli(f.lastModified())));
                result.add(entry);
            }
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/{filename:.+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getDump(@PathVariable String filename) throws IOException {
        if (filename.contains("..") || filename.contains("/")) {
            return ResponseEntity.badRequest().build();
        }
        File file = new File(DUMP_DIR, filename);
        if (!file.exists() || !file.getCanonicalPath().startsWith(DUMP_DIR.getCanonicalPath())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Files.readString(file.toPath()));
    }

    @GetMapping(value = "/view", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> viewerPage() {
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8">
                  <title>Elliott Debug Viewer</title>
                  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/jsoneditor/10.1.0/jsoneditor.min.css">
                  <script src="https://cdnjs.cloudflare.com/ajax/libs/jsoneditor/10.1.0/jsoneditor.min.js"></script>
                  <style>
                    body { font-family: monospace; background: #1a1a2e; color: #e0e0e0; margin: 0; padding: 16px; }
                    h2 { color: #90caf9; }
                    select, button { background: #263238; color: #e0e0e0; border: 1px solid #455a64;
                                     padding: 6px 12px; margin: 4px; border-radius: 4px; font-size: 14px; cursor: pointer; }
                    select { min-width: 320px; }
                    #editor { height: calc(100vh - 120px); margin-top: 12px; }
                    .toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
                  </style>
                </head>
                <body>
                  <h2>Elliott Debug Viewer</h2>
                  <div class="toolbar">
                    <select id="fileSelect"><option value="">-- select dump file --</option></select>
                    <button onclick="loadFile()">Load</button>
                    <button onclick="refreshList()">Refresh</button>
                    <span id="status" style="color:#80cbc4;font-size:13px"></span>
                  </div>
                  <div id="editor"></div>
                  <script>
                    const editor = new JSONEditor(document.getElementById('editor'), { mode: 'view', modes: ['view','tree','code'] });

                    async function refreshList() {
                      const res = await fetch('/api/debug/elliott');
                      const files = await res.json();
                      const sel = document.getElementById('fileSelect');
                      const cur = sel.value;
                      sel.innerHTML = '<option value="">-- select dump file --</option>';
                      files.forEach(f => {
                        const opt = document.createElement('option');
                        opt.value = f.filename;
                        opt.textContent = f.filename + '  (' + (f.size/1024).toFixed(1) + 'KB, ' + f.modified + ')';
                        sel.appendChild(opt);
                      });
                      if (cur) sel.value = cur;
                      document.getElementById('status').textContent = files.length + ' files';
                    }

                    async function loadFile() {
                      const filename = document.getElementById('fileSelect').value;
                      if (!filename) return;
                      document.getElementById('status').textContent = 'Loading...';
                      const res = await fetch('/api/debug/elliott/' + filename);
                      const json = await res.json();
                      editor.set(json);
                      editor.expandAll();
                      document.getElementById('status').textContent = 'Loaded: ' + filename;
                    }

                    refreshList().then(() => {
                      const params = new URLSearchParams(window.location.search);
                      const file = params.get('file');
                      if (file) {
                        document.getElementById('fileSelect').value = file;
                        loadFile();
                      }
                    });
                  </script>
                </body>
                </html>
                """;
        return ResponseEntity.ok(html);
    }
}
