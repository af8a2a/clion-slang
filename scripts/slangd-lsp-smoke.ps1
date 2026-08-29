param(
    [string] $Slangd = "slangd",
    [string] $Workspace = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"

$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $Slangd
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true

$serverProcess = [System.Diagnostics.Process]::new()
$serverProcess.StartInfo = $startInfo
if (-not $serverProcess.Start()) {
    throw "Failed to start slangd: $Slangd"
}

function Send-LspMessage([hashtable] $Message) {
    $json = $Message | ConvertTo-Json -Compress -Depth 20
    $payload = [System.Text.Encoding]::UTF8.GetBytes($json)
    $header = [System.Text.Encoding]::ASCII.GetBytes("Content-Length: $($payload.Length)`r`n`r`n")
    $serverProcess.StandardInput.BaseStream.Write($header, 0, $header.Length)
    $serverProcess.StandardInput.BaseStream.Write($payload, 0, $payload.Length)
    $serverProcess.StandardInput.BaseStream.Flush()
}

function Read-LspMessage {
    $headerBytes = [System.Collections.Generic.List[byte]]::new()
    $tail = ""
    while ($tail -ne "`r`n`r`n") {
        $value = $serverProcess.StandardOutput.BaseStream.ReadByte()
        if ($value -lt 0) {
            throw "slangd closed stdout before sending an LSP response"
        }
        $headerBytes.Add([byte]$value)
        $tail = ($tail + [char]$value)
        if ($tail.Length -gt 4) {
            $tail = $tail.Substring($tail.Length - 4)
        }
    }

    $header = [System.Text.Encoding]::ASCII.GetString($headerBytes.ToArray())
    $match = [regex]::Match($header, "(?im)^Content-Length:\s*(\d+)\s*$")
    if (-not $match.Success) {
        throw "LSP response did not contain Content-Length: $header"
    }

    $length = [int]$match.Groups[1].Value
    $payload = [byte[]]::new($length)
    $offset = 0
    while ($offset -lt $length) {
        $read = $serverProcess.StandardOutput.BaseStream.Read($payload, $offset, $length - $offset)
        if ($read -le 0) {
            throw "slangd closed stdout during an LSP response"
        }
        $offset += $read
    }
    return ([System.Text.Encoding]::UTF8.GetString($payload) | ConvertFrom-Json)
}

function Read-LspResponse([int] $ExpectedId) {
    while ($true) {
        $message = Read-LspMessage
        if ($null -eq $message.method -and $message.id -eq $ExpectedId) {
            return $message
        }

        # slangd may request workspace configuration immediately after the
        # initialized notification. A smoke client has no custom settings, but
        # it still needs to answer server-initiated requests before shutdown.
        if ($null -ne $message.method -and $null -ne $message.id) {
            $result = $null
            if ($message.method -eq "workspace/configuration") {
                $result = @()
                foreach ($unused in $message.params.items) {
                    $result += @{}
                }
            }
            Send-LspMessage @{ jsonrpc = "2.0"; id = $message.id; result = $result }
        }
    }
}

try {
    $workspaceUri = ([System.Uri](Resolve-Path -LiteralPath $Workspace).Path).AbsoluteUri
    Send-LspMessage @{
        jsonrpc = "2.0"
        id = 1
        method = "initialize"
        params = @{
            processId = $null
            rootUri = $workspaceUri
            capabilities = @{}
            workspaceFolders = @(@{ uri = $workspaceUri; name = "slang-smoke" })
        }
    }

    $initialize = Read-LspResponse 1
    if ($initialize.id -ne 1 -or $null -eq $initialize.result.capabilities) {
        throw "slangd returned an invalid initialize response: $($initialize | ConvertTo-Json -Compress -Depth 10)"
    }

    $capabilities = $initialize.result.capabilities
    $required = @("completionProvider", "hoverProvider", "definitionProvider", "semanticTokensProvider")
    foreach ($name in $required) {
        if ($null -eq $capabilities.$name -or $capabilities.$name -eq $false) {
            throw "slangd initialize response is missing required capability: $name"
        }
    }

    Send-LspMessage @{ jsonrpc = "2.0"; method = "initialized"; params = @{} }
    Send-LspMessage @{ jsonrpc = "2.0"; id = 2; method = "shutdown"; params = $null }
    $shutdown = Read-LspResponse 2
    if ($shutdown.id -ne 2) {
        throw "slangd returned an invalid shutdown response"
    }
    Send-LspMessage @{ jsonrpc = "2.0"; method = "exit"; params = $null }

    [pscustomobject]@{
        Executable = $Slangd
        Completion = $true
        Hover = $true
        Definition = $true
        SemanticTokens = $true
        InlayHints = ($null -ne $capabilities.inlayHintProvider)
        Formatting = ($null -ne $capabilities.documentFormattingProvider)
    }
}
finally {
    if (-not $serverProcess.HasExited) {
        $serverProcess.Kill($true)
    }
    $serverProcess.Dispose()
}
