# Browser terminal demo

This demo uses Kinetica's shared Kotlin terminal engine and WebGL2 glyph-atlas renderer. It provides local
echo and `help`, `colors`, `clear`, and `flood` commands. It does not run a remote shell.

```sh
./kotlin build -m browser-terminal -p js -v release
python3 -m http.server 4173 --bind 127.0.0.1
```

Open <http://127.0.0.1:4173/samples/browser-terminal/web/index.html>.
Append `?renderer=gpu` to require WebGL2 or `?renderer=software` for Canvas 2D. The default
automatically falls back to Canvas 2D when WebGL2 cannot be used.
See [the terminal module](../../kinetica-terminal/README.md) for PTY transport integration.
