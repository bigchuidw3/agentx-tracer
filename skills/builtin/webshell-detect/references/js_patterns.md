# JS (Node.js) Dangerous Patterns — 7 Behavioral Categories

Node.js Webshells target server-side JavaScript environments. Common in Express, Koa, and custom Node.js backends.

## Category 1: Code Execution

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `eval()` | `eval(code)` | JS direct eval — exact equivalent of PHP's eval |
| `Function()` | `new Function(arg, body)` | Creates function from string. Similar to eval. |
| `require('child_process')` | `require("child_process")` | Loads process execution module |
| `import('child_process')` | `import("child_process")` | Dynamic import for ESM |
| `vm.runInThisContext()` | `vm.runInThisContext(code)` | Node.js VM execution |
| `vm.runInNewContext()` | `vm.runInNewContext(code, sandbox)` | Sandboxed code execution |
| `vm.Script()` | `new vm.Script(code)` | Compiles code for later execution |
| `vm.compileFunction()` | `vm.compileFunction(code)` | Compiles to function |
| `setImmediate()` | `setImmediate(callback)` | Schedules immediate callback execution |

## Category 2: Program Execution

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `exec()` | `exec(cmd)` | child_process.exec — most common |
| `execSync()` | `execSync(cmd)` | Synchronous variant |
| `spawn()` | `spawn(cmd, args)` | More granular process control |
| `spawnSync()` | `spawnSync(cmd, args)` | Synchronous spawn |
| `fork()` | `fork(modulePath)` | Creates child Node.js process |
| `child_process.exec()` | `child_process.exec(cmd)` | Full path variant |
| `child_process.spawn()` | `child_process.spawn(cmd)` | Full path variant |
| `ShellExecute` | Windows-specific | Via ActiveX or WScript in JScript |

## Category 3: Obfuscation & Encryption

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `atob()` | `atob(encoded)` | Base64 decode (browser API) |
| `btoa()` | `btoa(data)` | Base64 encode |
| `Buffer.from().toString('base64')` | Base64 via Buffer | Node.js base64 decode |
| `Buffer.from(string, 'base64')` | Base64 decode variant | Alternative base64 decode |
| `String.fromCharCode()` | `String.fromCharCode(...codes)` | Char code to string — ASCII obfuscation |
| `String.fromCodePoint()` | `String.fromCodePoint(...)` | Unicode variant |
| `unescape()` | `unescape(str)` | URL decode |
| `decodeURIComponent()` | `decodeURIComponent(str)` | URI decode |
| `crypto.createDecipher()` | AES/RSA decryption | Node.js crypto |
| XOR operations | `char ^= key`, byte-wise XOR loops | Obfuscated payload decoding |

## Category 4: Information Gathering

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `process.env` | `process.env.PATH` etc. | Environment variables |
| `process.cwd()` | `process.cwd()` | Current working directory |
| `process.platform` | `process.platform` | OS type |
| `process.arch` | `process.arch` | CPU architecture |
| `process.pid` | `process.pid` | Process ID |
| `process.versions` | `process.versions` | Node.js and dependency versions |
| `os.hostname()` | `os.hostname()` | Host name |
| `os.userInfo()` | `os.userInfo()` | Current user info |
| `os.networkInterfaces()` | `os.networkInterfaces()` | Network info |
| `os.cpus()` | `os.cpus()` | CPU info |
| `__dirname` | `__dirname` | Current script directory |
| `__filename` | `__filename` | Current script path |

## Category 5: Network Communication

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `require('http')` | `require("http")` | HTTP module — C2 or exfiltration |
| `require('https')` | `require("https")` | HTTPS module |
| `require('net')` | `require("net")` | TCP socket — reverse shell |
| `require('dgram')` | `require("dgram")` | UDP socket |
| `require('ws')` / `require('socket.io')` | WebSocket modules | Persistent C2 |
| `require('request')` / `require('axios')` | HTTP client libraries | File download / C2 |
| `fetch()` | `fetch(url)` | Modern HTTP client |
| `net.createConnection()` | `net.createConnection(port, host)` | TCP reverse shell |
| `net.createServer()` | `net.createServer(callback)` | Bind shell |
| `require('dns')` | `require("dns")` | DNS queries for data exfiltration |

## Category 6: Callback / Reflection

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `setTimeout()` | `setTimeout(callback, delay)` | Delayed code execution |
| `setInterval()` | `setInterval(callback, interval)` | Repeated code execution |
| `.then()` | `.then(callback)` | Promise chaining |
| `.catch()` | `.catch(callback)` | Promise error handling |
| `Proxy()` | `new Proxy(target, handler)` | Dynamic property interception |
| `Reflect.construct()` | `Reflect.construct(target, args)` | Reflective instantiation |
| `Reflect.apply()` | `Reflect.apply(target, thisArg, args)` | Reflective function call |
| `require.resolve()` | `require.resolve(module)` | Module path resolution |
| `.bind()` / `.call()` / `.apply()` | Function binding | Indirect function invocation |

## Category 7: File Operations

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `require('fs')` | `require("fs")` | File system module |
| `fs.writeFileSync()` | `fs.writeFileSync(path, data)` | Write file — most common in webshells |
| `fs.writeFile()` | `fs.writeFile(path, data, cb)` | Async write file |
| `fs.readFileSync()` | `fs.readFileSync(path)` | Read file |
| `fs.readFile()` | `fs.readFile(path, cb)` | Async read file |
| `fs.appendFileSync()` | `fs.appendFileSync(path, data)` | Append to file |
| `fs.unlinkSync()` | `fs.unlinkSync(path)` | Delete file |
| `fs.mkdirSync()` | `fs.mkdirSync(path)` | Create directory |
| `require('fs-extra')` | Extended FS operations | Common npm package for file ops |

## Common JS Webshell Patterns

### Pattern 1: Classic exec
```javascript
const { exec } = require('child_process');
exec(process.argv[2], (err, stdout) => { console.log(stdout); });
// Attack: node shell.js whoami
```
Chain: `process.argv` → `exec()`

### Pattern 2: eval with POST
```javascript
eval(Buffer.from(req.body.code, 'base64').toString());
```
Chain: `req.body` → `Buffer.from().toString()` → `eval()`

### Pattern 3: File upload
```javascript
const fs = require('fs');
fs.writeFileSync(req.body.path, req.body.content);
```
Chain: `req.body` → `fs.writeFileSync()`
