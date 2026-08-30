param(
    [string] $Slangd = "slangd",
    [string] $Workspace = (Split-Path -Parent $PSScriptRoot),
    [string] $DefinitionFile = (Join-Path (Split-Path -Parent $PSScriptRoot) "src\test\testData\slang\Definition.slang"),
    [int] $DefinitionLine = 8,
    [int] $DefinitionCharacter = 13,
    [string] $ExpectedTargetFileName = "Definition.slang",
    [int] $ExpectedTargetLine = 1,
    [int] $ExpectedTargetCharacter = 6,
    [string] $SemanticFile = (Join-Path (Split-Path -Parent $PSScriptRoot) "src\test\testData\slang\SemanticHighlighting.slang"),
    [ValidateSet("stock", "enhanced", "none")]
    [string] $SemanticContract = "stock",
    [string] $SemanticContractFile = (Join-Path (Split-Path -Parent $PSScriptRoot) "src\test\testData\lsp\semantic-tokens-contract.json"),
    [ValidateRange(1, 300)]
    [int] $ResponseTimeoutSeconds = 20,
    [switch] $AsJson
)

$ErrorActionPreference = "Stop"

$slangdCommand = Get-Command -Name $Slangd -CommandType Application -ErrorAction Stop |
    Select-Object -First 1
if ($null -eq $slangdCommand -or [string]::IsNullOrWhiteSpace($slangdCommand.Source)) {
    throw "Unable to resolve slangd executable: $Slangd"
}
$resolvedSlangdPath = $slangdCommand.Source
$slangdSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedSlangdPath).Hash.ToLowerInvariant()

# LSP 3.17 standard token vocabulary advertised by this smoke client. The
# server still supplies the authoritative, index-ordered legend in its
# initialize response.
$clientSemanticTokenTypes = @(
    "namespace",
    "type",
    "class",
    "enum",
    "interface",
    "struct",
    "typeParameter",
    "parameter",
    "variable",
    "property",
    "enumMember",
    "event",
    "function",
    "method",
    "macro",
    "keyword",
    "modifier",
    "comment",
    "string",
    "number",
    "regexp",
    "operator",
    "decorator"
)
$clientSemanticTokenModifiers = @(
    "declaration",
    "definition",
    "readonly",
    "static",
    "deprecated",
    "abstract",
    "async",
    "modification",
    "documentation",
    "defaultLibrary"
)
if ($SemanticContract -eq "stock") {
    $clientSemanticTokenTypes = @(
        "type",
        "enumMember",
        "variable",
        "parameter",
        "function",
        "property",
        "namespace",
        "keyword",
        "macro",
        "string"
    )
    $clientSemanticTokenModifiers = @()
}

$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $resolvedSlangdPath
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

# Drain stderr from process start so a noisy server cannot block on a full
# redirected pipe. The catch block appends the captured text to failures.
$stderrTask = $serverProcess.StandardError.ReadToEndAsync()

function Stop-ServerProcess {
    if ($serverProcess.HasExited) {
        return
    }

    try {
        # Kill(Boolean) is available on the .NET runtime used by current CLion
        # and PowerShell. Fall back to Kill() for Windows PowerShell 5.1.
        $serverProcess.Kill($true)
    }
    catch [System.Management.Automation.MethodException] {
        $serverProcess.Kill()
    }
    catch [System.MissingMethodException] {
        $serverProcess.Kill()
    }
    $null = $serverProcess.WaitForExit(5000)
}

function Send-LspMessage([hashtable] $Message) {
    $json = $Message | ConvertTo-Json -Compress -Depth 20
    $payload = [System.Text.Encoding]::UTF8.GetBytes($json)
    $header = [System.Text.Encoding]::ASCII.GetBytes("Content-Length: $($payload.Length)`r`n`r`n")
    $serverProcess.StandardInput.BaseStream.Write($header, 0, $header.Length)
    $serverProcess.StandardInput.BaseStream.Write($payload, 0, $payload.Length)
    $serverProcess.StandardInput.BaseStream.Flush()
}

function Read-LspChunk(
    [byte[]] $Buffer,
    [int] $Offset,
    [int] $Count,
    [datetime] $Deadline,
    [string] $Context
) {
    $remainingMilliseconds = [int][Math]::Ceiling(($Deadline - [datetime]::UtcNow).TotalMilliseconds)
    if ($remainingMilliseconds -le 0) {
        throw "Timed out after $ResponseTimeoutSeconds seconds while reading $Context"
    }

    $readTask = $serverProcess.StandardOutput.BaseStream.ReadAsync($Buffer, $Offset, $Count)
    if (-not $readTask.Wait($remainingMilliseconds)) {
        throw "Timed out after $ResponseTimeoutSeconds seconds while reading $Context"
    }
    return $readTask.GetAwaiter().GetResult()
}

function Read-LspMessage {
    $deadline = [datetime]::UtcNow.AddSeconds($ResponseTimeoutSeconds)
    $headerBytes = [System.Collections.Generic.List[byte]]::new()
    $tail = ""
    $singleByte = [byte[]]::new(1)
    while ($tail -ne "`r`n`r`n") {
        $read = Read-LspChunk $singleByte 0 1 $deadline "an LSP header"
        if ($read -le 0) {
            throw "slangd closed stdout before sending an LSP response"
        }
        $value = $singleByte[0]
        $headerBytes.Add($value)
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
        $read = Read-LspChunk $payload $offset ($length - $offset) $deadline "an LSP payload"
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
            if ($null -ne $message.error) {
                throw "slangd returned an error for request $ExpectedId`: $($message.error | ConvertTo-Json -Compress -Depth 10)"
            }
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

function Test-IsJsonNumber($Value) {
    return (
        $Value -is [byte] -or
        $Value -is [sbyte] -or
        $Value -is [int16] -or
        $Value -is [uint16] -or
        $Value -is [int32] -or
        $Value -is [uint32] -or
        $Value -is [int64] -or
        $Value -is [uint64] -or
        $Value -is [single] -or
        $Value -is [double] -or
        $Value -is [decimal]
    )
}

function ConvertTo-LspUInt32($Value, [string] $FieldName, [int] $DataIndex) {
    if ($null -eq $Value) {
        throw "semantic token data[$DataIndex] ($FieldName) is null"
    }
    if (-not (Test-IsJsonNumber $Value)) {
        $actualType = $Value.GetType().FullName
        throw "semantic token data[$DataIndex] ($FieldName) must be a JSON number, got $actualType"
    }

    try {
        $number = [System.Convert]::ToDecimal($Value, [System.Globalization.CultureInfo]::InvariantCulture)
    }
    catch {
        throw "semantic token data[$DataIndex] ($FieldName) is not an integer: $Value"
    }
    if ($number -ne [decimal]::Truncate($number) -or
        $number -lt 0 -or
        $number -gt [uint32]::MaxValue) {
        throw "semantic token data[$DataIndex] ($FieldName) is not an LSP uinteger: $Value"
    }
    return [uint64]$number
}

function Assert-Legend([string[]] $Entries, [string] $LegendName, [bool] $RequireNonEmpty) {
    if ($RequireNonEmpty -and $Entries.Count -eq 0) {
        throw "semantic token legend.$LegendName must not be empty"
    }

    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    for ($index = 0; $index -lt $Entries.Count; $index++) {
        $entry = $Entries[$index]
        if ([string]::IsNullOrWhiteSpace($entry)) {
            throw "semantic token legend.$LegendName[$index] is empty"
        }
        if (-not $seen.Add($entry)) {
            throw "semantic token legend.$LegendName contains a duplicate entry: $entry"
        }
    }
}

function Test-OrdinalContains([string[]] $Actual, [string] $Expected) {
    foreach ($item in $Actual) {
        if ([string]::Equals($item, $Expected, [System.StringComparison]::Ordinal)) {
            return $true
        }
    }
    return $false
}

function Assert-JsonObject($Value, [string] $Description) {
    if ($null -eq $Value -or -not ($Value -is [System.Management.Automation.PSCustomObject])) {
        $actualType = if ($null -eq $Value) { "null" } else { $Value.GetType().FullName }
        throw "$Description must be a JSON object, got $actualType"
    }
}

function Get-JsonStringArray(
    $Object,
    [string] $PropertyName,
    [string] $Description,
    [bool] $RequireNonEmpty
) {
    Assert-JsonObject $Object $Description
    $property = $Object.PSObject.Properties[$PropertyName]
    if ($null -eq $property) {
        throw "$Description is missing required array '$PropertyName'"
    }
    if (-not ($property.Value -is [System.Array])) {
        $actualType = if ($null -eq $property.Value) { "null" } else { $property.Value.GetType().FullName }
        throw "$Description.$PropertyName must be a JSON array, got $actualType"
    }

    $values = @($property.Value)
    if ($RequireNonEmpty -and $values.Count -eq 0) {
        throw "$Description.$PropertyName must not be empty"
    }

    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    for ($index = 0; $index -lt $values.Count; $index++) {
        if (-not ($values[$index] -is [string]) -or [string]::IsNullOrWhiteSpace($values[$index])) {
            $actualType = if ($null -eq $values[$index]) { "null" } else { $values[$index].GetType().FullName }
            throw "$Description.$PropertyName[$index] must be a non-empty JSON string, got $actualType"
        }
        if (-not $seen.Add($values[$index])) {
            throw "$Description.$PropertyName contains duplicate entry '$($values[$index])'"
        }
    }

    return [string[]]$values
}

function Assert-SemanticContractProfile($Profile, [string] $ProfileName) {
    $description = "semantic token contract profile '$ProfileName'"
    Assert-JsonObject $Profile $description
    Assert-JsonObject $Profile.legend "$description.legend"

    if ([string]::Equals($ProfileName, "stock", [System.StringComparison]::Ordinal)) {
        $legendTypes = @(Get-JsonStringArray $Profile.legend "tokenTypesExact" "$description.legend" $true)
        $legendModifiers = @(Get-JsonStringArray $Profile.legend "tokenModifiersExact" "$description.legend" $false)
    } else {
        $legendTypes = @(Get-JsonStringArray $Profile.legend "tokenTypesRequired" "$description.legend" $true)
        $legendModifiers = @(Get-JsonStringArray $Profile.legend "tokenModifiersRequired" "$description.legend" $true)
    }

    $expectationsProperty = $Profile.PSObject.Properties["expectedTokens"]
    if ($null -eq $expectationsProperty) {
        throw "$description is missing required array 'expectedTokens'"
    }
    if (-not ($expectationsProperty.Value -is [System.Array])) {
        $actualType = if ($null -eq $expectationsProperty.Value) { "null" } else { $expectationsProperty.Value.GetType().FullName }
        throw "$description.expectedTokens must be a JSON array, got $actualType"
    }
    $expectations = @($expectationsProperty.Value)
    if ($expectations.Count -eq 0) {
        throw "$description.expectedTokens must not be empty"
    }

    for ($index = 0; $index -lt $expectations.Count; $index++) {
        $expected = $expectations[$index]
        $expectedDescription = "$description.expectedTokens[$index]"
        Assert-JsonObject $expected $expectedDescription

        foreach ($requiredName in @("text", "type")) {
            $requiredProperty = $expected.PSObject.Properties[$requiredName]
            if ($null -eq $requiredProperty -or
                -not ($requiredProperty.Value -is [string]) -or
                [string]::IsNullOrWhiteSpace($requiredProperty.Value)) {
                throw "$expectedDescription.$requiredName must be a non-empty JSON string"
            }
        }
        if (-not (Test-OrdinalContains $legendTypes ([string]$expected.type))) {
            throw "$expectedDescription.type '$($expected.type)' is absent from the profile legend"
        }

        $lineProperty = $expected.PSObject.Properties["line"]
        if ($null -ne $lineProperty) {
            $line = ConvertTo-LspUInt32 $lineProperty.Value "contract line" $index
            if ($line -gt [int]::MaxValue) {
                throw "$expectedDescription.line is too large: $line"
            }
        }

        $characterProperty = $expected.PSObject.Properties["character"]
        if ($null -ne $characterProperty) {
            $character = ConvertTo-LspUInt32 $characterProperty.Value "contract character" $index
            if ($character -gt [int]::MaxValue) {
                throw "$expectedDescription.character is too large: $character"
            }
        }

        $minimumCountProperty = $expected.PSObject.Properties["minimumCount"]
        if ($null -ne $minimumCountProperty) {
            $minimumCount = ConvertTo-LspUInt32 $minimumCountProperty.Value "contract minimumCount" $index
            if ($minimumCount -eq 0 -or $minimumCount -gt [int]::MaxValue) {
                throw "$expectedDescription.minimumCount must be a positive integer"
            }
        }

        $expectedModifiers = @()
        $forbiddenModifiers = @()
        foreach ($modifierPropertyName in @("modifiers", "forbiddenModifiers")) {
            $modifiersProperty = $expected.PSObject.Properties[$modifierPropertyName]
            if ($null -eq $modifiersProperty) {
                continue
            }

            $validatedModifiers = @(
                Get-JsonStringArray $expected $modifierPropertyName $expectedDescription $false
            )
            foreach ($modifier in $validatedModifiers) {
                if (-not (Test-OrdinalContains $legendModifiers $modifier)) {
                    throw "$expectedDescription.$modifierPropertyName modifier '$modifier' is absent from the profile legend"
                }
            }
            if ($modifierPropertyName -eq "modifiers") {
                $expectedModifiers = @($validatedModifiers)
            } else {
                $forbiddenModifiers = @($validatedModifiers)
            }
        }
        foreach ($modifier in $expectedModifiers) {
            if (Test-OrdinalContains $forbiddenModifiers $modifier) {
                throw "$expectedDescription modifier '$modifier' cannot be both required and forbidden"
            }
        }
    }
}

function Assert-SemanticContractDocument($Contract) {
    Assert-JsonObject $Contract "semantic token contract"
    $schemaProperty = $Contract.PSObject.Properties["schemaVersion"]
    if ($null -eq $schemaProperty) {
        throw "semantic token contract is missing schemaVersion"
    }
    $schemaVersion = ConvertTo-LspUInt32 $schemaProperty.Value "contract schemaVersion" 0
    if ($schemaVersion -ne 2) {
        throw "unsupported semantic token contract schemaVersion: $schemaVersion"
    }

    $profilesProperty = $Contract.PSObject.Properties["profiles"]
    if ($null -eq $profilesProperty) {
        throw "semantic token contract is missing profiles"
    }
    Assert-JsonObject $profilesProperty.Value "semantic token contract.profiles"
    $profileNames = @($profilesProperty.Value.PSObject.Properties.Name)
    if ($profileNames.Count -ne 2 -or
        -not (Test-OrdinalContains $profileNames "stock") -or
        -not (Test-OrdinalContains $profileNames "enhanced")) {
        throw "semantic token contract.profiles must contain exactly 'stock' and 'enhanced'"
    }

    Assert-SemanticContractProfile $profilesProperty.Value.stock "stock"
    Assert-SemanticContractProfile $profilesProperty.Value.enhanced "enhanced"
    return [int]$schemaVersion
}

function Assert-OrdinalArrayExact([string[]] $Actual, [object[]] $Expected, [string] $Description) {
    if ($Actual.Count -ne $Expected.Count) {
        throw "$Description differs: expected $($Expected.Count) entries, got $($Actual.Count). Expected [$($Expected -join ', ')], got [$($Actual -join ', ')]"
    }
    for ($index = 0; $index -lt $Actual.Count; $index++) {
        if (-not [string]::Equals($Actual[$index], [string]$Expected[$index], [System.StringComparison]::Ordinal)) {
            throw "$Description differs at index $index`: expected '$($Expected[$index])', got '$($Actual[$index])'"
        }
    }
}

function Assert-OrdinalArrayRequired([string[]] $Actual, [object[]] $Required, [string] $Description) {
    foreach ($requiredEntry in $Required) {
        if (-not (Test-OrdinalContains $Actual ([string]$requiredEntry))) {
            throw "$Description is missing required entry '$requiredEntry'. Actual: [$($Actual -join ', ')]"
        }
    }
}

function Decode-SemanticTokens(
    [object[]] $Data,
    [string[]] $TokenTypes,
    [string[]] $TokenModifiers,
    [string] $SourceText
) {
    if ($Data.Count -eq 0) {
        throw "slangd returned zero semantic tokens"
    }
    if (($Data.Count % 5) -ne 0) {
        throw "semantic token data length must be divisible by 5, got $($Data.Count)"
    }
    if ($TokenModifiers.Count -gt 32) {
        throw "semantic token legend declares $($TokenModifiers.Count) modifiers, but the LSP bitset supports at most 32"
    }

    # .NET String.Length and Substring use UTF-16 code units, matching the only
    # position encoding advertised by this client.
    $sourceLines = @([regex]::Split($SourceText, "\r\n|\n|\r"))
    $decoded = [System.Collections.Generic.List[object]]::new()
    [uint64] $previousLine = 0
    [uint64] $previousCharacter = 0
    [uint64] $previousEndCharacter = 0

    for ($offset = 0; $offset -lt $Data.Count; $offset += 5) {
        $tokenIndex = [int]($offset / 5)
        $deltaLine = ConvertTo-LspUInt32 $Data[$offset] "deltaLine" $offset
        $deltaStart = ConvertTo-LspUInt32 $Data[$offset + 1] "deltaStart" ($offset + 1)
        $length = ConvertTo-LspUInt32 $Data[$offset + 2] "length" ($offset + 2)
        $typeIndex = ConvertTo-LspUInt32 $Data[$offset + 3] "tokenType" ($offset + 3)
        $modifierBits = ConvertTo-LspUInt32 $Data[$offset + 4] "tokenModifiers" ($offset + 4)

        $line = $previousLine + $deltaLine
        $character = if ($deltaLine -eq 0) {
            $previousCharacter + $deltaStart
        } else {
            $deltaStart
        }

        if ($line -ge [uint64]$sourceLines.Count) {
            throw "semantic token $tokenIndex starts on line $line, outside the $($sourceLines.Count)-line UTF-16 document"
        }
        if ($length -eq 0) {
            throw "semantic token $tokenIndex at $line`:$character has zero length"
        }
        if ($typeIndex -ge [uint64]$TokenTypes.Count) {
            throw "semantic token $tokenIndex has type index $typeIndex, outside legend.tokenTypes[0..$($TokenTypes.Count - 1)]"
        }
        if ($TokenModifiers.Count -eq 0) {
            if ($modifierBits -ne 0) {
                throw "semantic token $tokenIndex sets modifier bits $modifierBits, but legend.tokenModifiers is empty"
            }
        }
        elseif (($modifierBits -shr $TokenModifiers.Count) -ne 0) {
            throw "semantic token $tokenIndex sets modifier bits outside legend.tokenModifiers: $modifierBits"
        }

        $sourceLine = $sourceLines[[int]$line]
        $endCharacter = $character + $length
        if ($character -gt [uint64]$sourceLine.Length -or $endCharacter -gt [uint64]$sourceLine.Length) {
            throw "semantic token $tokenIndex range $line`:$character-$endCharacter exceeds UTF-16 line length $($sourceLine.Length)"
        }
        if ($tokenIndex -gt 0 -and $deltaLine -eq 0 -and $character -lt $previousEndCharacter) {
            throw "semantic token $tokenIndex overlaps or precedes the prior token on line $line ($character < $previousEndCharacter)"
        }

        $activeModifiers = @()
        for ($modifierIndex = 0; $modifierIndex -lt $TokenModifiers.Count; $modifierIndex++) {
            $modifierMask = [uint64]1 -shl $modifierIndex
            if (($modifierBits -band $modifierMask) -ne 0) {
                $activeModifiers += $TokenModifiers[$modifierIndex]
            }
        }

        $decoded.Add([pscustomobject][ordered]@{
            Index = $tokenIndex
            Line = [int]$line
            Character = [int]$character
            Length = [int]$length
            EndCharacter = [int]$endCharacter
            Type = $TokenTypes[[int]$typeIndex]
            TypeIndex = [int]$typeIndex
            Modifiers = @($activeModifiers)
            ModifierBits = [uint32]$modifierBits
            Text = $sourceLine.Substring([int]$character, [int]$length)
        })

        $previousLine = $line
        $previousCharacter = $character
        $previousEndCharacter = $endCharacter
    }

    return $decoded
}

function Assert-SemanticContract(
    $Profile,
    [string[]] $TokenTypes,
    [string[]] $TokenModifiers,
    [object[]] $DecodedTokens
) {
    if ($null -eq $Profile.legend) {
        throw "semantic token contract profile '$SemanticContract' has no legend"
    }

    $exactTypesProperty = $Profile.legend.PSObject.Properties["tokenTypesExact"]
    if ($null -ne $exactTypesProperty) {
        Assert-OrdinalArrayExact $TokenTypes @($exactTypesProperty.Value) "contract legend.tokenTypesExact"
    }
    $exactModifiersProperty = $Profile.legend.PSObject.Properties["tokenModifiersExact"]
    if ($null -ne $exactModifiersProperty) {
        Assert-OrdinalArrayExact $TokenModifiers @($exactModifiersProperty.Value) "contract legend.tokenModifiersExact"
    }
    $requiredTypesProperty = $Profile.legend.PSObject.Properties["tokenTypesRequired"]
    if ($null -ne $requiredTypesProperty) {
        Assert-OrdinalArrayRequired $TokenTypes @($requiredTypesProperty.Value) "contract legend.tokenTypesRequired"
    }
    $requiredModifiersProperty = $Profile.legend.PSObject.Properties["tokenModifiersRequired"]
    if ($null -ne $requiredModifiersProperty) {
        Assert-OrdinalArrayRequired $TokenModifiers @($requiredModifiersProperty.Value) "contract legend.tokenModifiersRequired"
    }

    $expectedTokensProperty = $Profile.PSObject.Properties["expectedTokens"]
    if ($null -eq $expectedTokensProperty) {
        throw "semantic token contract profile '$SemanticContract' has no expectedTokens"
    }

    $matches = [System.Collections.Generic.List[object]]::new()
    $expectationIndex = 0
    foreach ($expected in @($expectedTokensProperty.Value)) {
        $text = [string]$expected.text
        $type = [string]$expected.type
        if ([string]::IsNullOrEmpty($text) -or [string]::IsNullOrEmpty($type)) {
            throw "semantic token contract expectedTokens[$expectationIndex] must declare non-empty text and type"
        }

        $lineProperty = $expected.PSObject.Properties["line"]
        $expectedLine = $null
        if ($null -ne $lineProperty) {
            $lineValue = ConvertTo-LspUInt32 $lineProperty.Value "contract line" $expectationIndex
            if ($lineValue -gt [int]::MaxValue) {
                throw "semantic token contract expectedTokens[$expectationIndex].line is too large: $lineValue"
            }
            $expectedLine = [int]$lineValue
        }

        $characterProperty = $expected.PSObject.Properties["character"]
        $expectedCharacter = $null
        if ($null -ne $characterProperty) {
            $characterValue = ConvertTo-LspUInt32 $characterProperty.Value "contract character" $expectationIndex
            if ($characterValue -gt [int]::MaxValue) {
                throw "semantic token contract expectedTokens[$expectationIndex].character is too large: $characterValue"
            }
            $expectedCharacter = [int]$characterValue
        }

        $minimumCountProperty = $expected.PSObject.Properties["minimumCount"]
        $minimumCount = 1
        if ($null -ne $minimumCountProperty) {
            $minimumCountValue = ConvertTo-LspUInt32 $minimumCountProperty.Value "contract minimumCount" $expectationIndex
            if ($minimumCountValue -eq 0 -or $minimumCountValue -gt [int]::MaxValue) {
                throw "semantic token contract expectedTokens[$expectationIndex].minimumCount must be a positive integer"
            }
            $minimumCount = [int]$minimumCountValue
        }

        $modifiersProperty = $expected.PSObject.Properties["modifiers"]
        $requiredTokenModifiers = @()
        $modifiersSpecified = $null -ne $modifiersProperty
        if ($modifiersSpecified) {
            $requiredTokenModifiers = @($modifiersProperty.Value | ForEach-Object { [string]$_ })
        }

        $forbiddenModifiersProperty = $expected.PSObject.Properties["forbiddenModifiers"]
        $forbiddenTokenModifiers = @()
        if ($null -ne $forbiddenModifiersProperty) {
            $forbiddenTokenModifiers = @(
                $forbiddenModifiersProperty.Value | ForEach-Object { [string]$_ }
            )
        }

        $actualMatches = @()
        foreach ($actual in $DecodedTokens) {
            if (-not [string]::Equals([string]$actual.Text, $text, [System.StringComparison]::Ordinal) -or
                -not [string]::Equals([string]$actual.Type, $type, [System.StringComparison]::Ordinal)) {
                continue
            }
            if ($null -ne $expectedLine -and $actual.Line -ne $expectedLine) {
                continue
            }
            if ($null -ne $expectedCharacter -and $actual.Character -ne $expectedCharacter) {
                continue
            }
            if ($modifiersSpecified -and $requiredTokenModifiers.Count -eq 0 -and $actual.Modifiers.Count -ne 0) {
                continue
            }

            $hasRequiredModifiers = $true
            foreach ($requiredModifier in $requiredTokenModifiers) {
                if (-not (Test-OrdinalContains @($actual.Modifiers) $requiredModifier)) {
                    $hasRequiredModifiers = $false
                    break
                }
            }
            $hasForbiddenModifier = $false
            foreach ($forbiddenModifier in $forbiddenTokenModifiers) {
                if (Test-OrdinalContains @($actual.Modifiers) $forbiddenModifier) {
                    $hasForbiddenModifier = $true
                    break
                }
            }
            if ($hasRequiredModifiers -and -not $hasForbiddenModifier) {
                $actualMatches += $actual
            }
        }

        if ($actualMatches.Count -lt $minimumCount) {
            $positionDescription = if ($null -eq $expectedLine) {
                "any position"
            } elseif ($null -eq $expectedCharacter) {
                "line $expectedLine"
            } else {
                "position $expectedLine`:$expectedCharacter"
            }
            $modifierDescription = if (-not $modifiersSpecified) {
                "any modifiers"
            } elseif ($requiredTokenModifiers.Count -eq 0) {
                "no modifiers"
            } else {
                "required modifiers [$($requiredTokenModifiers -join ', ')]"
            }
            $forbiddenDescription = if ($forbiddenTokenModifiers.Count -eq 0) {
                ""
            } else {
                " and forbidden modifiers [$($forbiddenTokenModifiers -join ', ')]"
            }
            throw "semantic token contract expected at least $minimumCount occurrence(s) of '$text' as '$type' at $positionDescription with $modifierDescription$forbiddenDescription, got $($actualMatches.Count)"
        }

        $matches.Add([pscustomobject][ordered]@{
            Text = $text
            Type = $type
            Line = $expectedLine
            Character = $expectedCharacter
            Modifiers = if ($modifiersSpecified) { @($requiredTokenModifiers) } else { $null }
            ForbiddenModifiers = @($forbiddenTokenModifiers)
            MinimumCount = $minimumCount
            ActualCount = $actualMatches.Count
        })
        $expectationIndex++
    }
    return $matches
}

try {
    $workspaceUri = ([System.Uri](Resolve-Path -LiteralPath $Workspace).Path).AbsoluteUri
    $definitionPath = (Resolve-Path -LiteralPath $DefinitionFile).Path
    $definitionUri = ([System.Uri]$definitionPath).AbsoluteUri
    $definitionText = [System.IO.File]::ReadAllText($definitionPath)
    $semanticPath = (Resolve-Path -LiteralPath $SemanticFile).Path
    $semanticUri = ([System.Uri]$semanticPath).AbsoluteUri
    $semanticText = [System.IO.File]::ReadAllText($semanticPath)

    $contract = $null
    $contractProfile = $null
    $contractSchemaVersion = $null
    if ($SemanticContract -ne "none") {
        $contractPath = (Resolve-Path -LiteralPath $SemanticContractFile).Path
        $contract = [System.IO.File]::ReadAllText($contractPath) | ConvertFrom-Json
        $contractSchemaVersion = Assert-SemanticContractDocument $contract
        $profileProperty = $contract.profiles.PSObject.Properties[$SemanticContract]
        $contractProfile = $profileProperty.Value
    }

    Send-LspMessage @{
        jsonrpc = "2.0"
        id = 1
        method = "initialize"
        params = @{
            processId = $null
            rootUri = $workspaceUri
            capabilities = @{
                general = @{
                    positionEncodings = @("utf-16")
                }
                workspace = @{ configuration = $true; workspaceFolders = $true }
                textDocument = @{
                    definition = @{ linkSupport = $true }
                    semanticTokens = @{
                        dynamicRegistration = $false
                        requests = @{ range = $false; full = $true }
                        tokenTypes = $clientSemanticTokenTypes
                        tokenModifiers = $clientSemanticTokenModifiers
                        formats = @("relative")
                        overlappingTokenSupport = $false
                        multilineTokenSupport = $false
                        serverCancelSupport = $false
                    }
                }
            }
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

    $positionEncoding = if ($null -eq $capabilities.positionEncoding) {
        "utf-16"
    } else {
        [string]$capabilities.positionEncoding
    }
    if (-not [string]::Equals($positionEncoding, "utf-16", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "slangd selected unsupported position encoding '$positionEncoding'; smoke validation requires UTF-16"
    }

    $semanticProvider = $capabilities.semanticTokensProvider
    if ($null -eq $semanticProvider.legend) {
        throw "slangd semanticTokensProvider is missing its legend"
    }
    $tokenTypes = @(Get-JsonStringArray $semanticProvider.legend "tokenTypes" "slangd semantic token legend" $true)
    $tokenModifiers = @(Get-JsonStringArray $semanticProvider.legend "tokenModifiers" "slangd semantic token legend" $false)
    Assert-Legend $tokenTypes "tokenTypes" $true
    Assert-Legend $tokenModifiers "tokenModifiers" $false

    $fullProperty = $semanticProvider.PSObject.Properties["full"]
    if ($null -eq $fullProperty -or $fullProperty.Value -eq $false) {
        throw "slangd semanticTokensProvider does not support textDocument/semanticTokens/full"
    }
    $rangeProperty = $semanticProvider.PSObject.Properties["range"]
    $rangeSupported = $false
    if ($null -ne $rangeProperty) {
        $rangeSupported = if ($rangeProperty.Value -is [bool]) { $rangeProperty.Value } else { $true }
    }

    Send-LspMessage @{ jsonrpc = "2.0"; method = "initialized"; params = @{} }
    Send-LspMessage @{
        jsonrpc = "2.0"
        method = "textDocument/didOpen"
        params = @{
            textDocument = @{
                uri = $definitionUri
                languageId = "slang"
                version = 1
                text = $definitionText
            }
        }
    }
    if (-not [string]::Equals($semanticUri, $definitionUri, [System.StringComparison]::OrdinalIgnoreCase)) {
        Send-LspMessage @{
            jsonrpc = "2.0"
            method = "textDocument/didOpen"
            params = @{
                textDocument = @{
                    uri = $semanticUri
                    languageId = "slang"
                    version = 1
                    text = $semanticText
                }
            }
        }
    }

    Send-LspMessage @{
        jsonrpc = "2.0"
        id = 2
        method = "textDocument/definition"
        params = @{
            textDocument = @{ uri = $definitionUri }
            position = @{ line = $DefinitionLine; character = $DefinitionCharacter }
        }
    }

    $definition = Read-LspResponse 2
    $definitionWireShape = if ($definition.result -is [System.Array]) { "array" } else { "singleton" }
    $definitionItems = @($definition.result)
    if ($definitionItems.Count -eq 0 -or $null -eq $definitionItems[0]) {
        throw "slangd returned no definition for $definitionUri at $DefinitionLine`:$DefinitionCharacter"
    }

    $firstDefinition = $definitionItems[0]
    $targetUri = if ($null -ne $firstDefinition.targetUri) {
        $firstDefinition.targetUri
    } else {
        $firstDefinition.uri
    }
    $targetRange = if ($null -ne $firstDefinition.targetSelectionRange) {
        $firstDefinition.targetSelectionRange
    } else {
        $firstDefinition.range
    }
    if ([string]::IsNullOrWhiteSpace($targetUri) -or $null -eq $targetRange) {
        throw "slangd returned an invalid definition: $($definition.result | ConvertTo-Json -Compress -Depth 10)"
    }
    if (-not $targetUri.EndsWith("/$ExpectedTargetFileName", [System.StringComparison]::OrdinalIgnoreCase) -or
        $targetRange.start.line -ne $ExpectedTargetLine -or
        $targetRange.start.character -ne $ExpectedTargetCharacter) {
        throw "slangd returned the wrong definition target: $targetUri at $($targetRange.start.line):$($targetRange.start.character)"
    }

    Send-LspMessage @{
        jsonrpc = "2.0"
        id = 3
        method = "textDocument/semanticTokens/full"
        params = @{
            textDocument = @{ uri = $semanticUri }
        }
    }
    $semanticResponse = Read-LspResponse 3
    if ($null -eq $semanticResponse.result -or $null -eq $semanticResponse.result.data) {
        throw "slangd returned no semanticTokens/full data for $semanticUri"
    }
    $semanticData = @($semanticResponse.result.data)
    $decodedTokens = @(Decode-SemanticTokens $semanticData $tokenTypes $tokenModifiers $semanticText)
    if ($decodedTokens.Count -eq 0) {
        throw "slangd returned zero decoded semantic tokens"
    }

    $contractMatches = @()
    if ($SemanticContract -ne "none") {
        $contractMatches = @(Assert-SemanticContract $contractProfile $tokenTypes $tokenModifiers $decodedTokens)
    }

    Send-LspMessage @{
        jsonrpc = "2.0"
        method = "textDocument/didClose"
        params = @{ textDocument = @{ uri = $definitionUri } }
    }
    if (-not [string]::Equals($semanticUri, $definitionUri, [System.StringComparison]::OrdinalIgnoreCase)) {
        Send-LspMessage @{
            jsonrpc = "2.0"
            method = "textDocument/didClose"
            params = @{ textDocument = @{ uri = $semanticUri } }
        }
    }
    Send-LspMessage @{ jsonrpc = "2.0"; id = 4; method = "shutdown"; params = $null }
    $shutdown = Read-LspResponse 4
    if ($shutdown.id -ne 4) {
        throw "slangd returned an invalid shutdown response"
    }
    Send-LspMessage @{ jsonrpc = "2.0"; method = "exit"; params = $null }

    $serverInfo = $null
    if ($null -ne $initialize.result.serverInfo) {
        $serverInfo = [pscustomobject][ordered]@{
            Name = [string]$initialize.result.serverInfo.name
            Version = [string]$initialize.result.serverInfo.version
        }
    }

    $resultId = $null
    $resultIdProperty = $semanticResponse.result.PSObject.Properties["resultId"]
    if ($null -ne $resultIdProperty -and $null -ne $resultIdProperty.Value) {
        $resultId = [string]$resultIdProperty.Value
    }
    $smokeResult = [pscustomobject][ordered]@{
        SchemaVersion = 1
        Executable = $resolvedSlangdPath
        ExecutableSha256 = $slangdSha256
        ServerInfo = $serverInfo
        PositionEncoding = $positionEncoding.ToLowerInvariant()
        Completion = $true
        Hover = $true
        Definition = "$targetUri`:$($targetRange.start.line + 1)"
        DefinitionWireShape = $definitionWireShape
        SemanticTokens = [pscustomobject][ordered]@{
            Full = $fullProperty.Value
            Range = $rangeSupported
            ResultId = $resultId
            DataIntegerCount = $semanticData.Count
            Count = $decodedTokens.Count
            Legend = [pscustomobject][ordered]@{
                TokenTypes = @($tokenTypes)
                TokenModifiers = @($tokenModifiers)
            }
            Tokens = @($decodedTokens)
        }
        SemanticContract = [pscustomobject][ordered]@{
            Profile = $SemanticContract
            SchemaVersion = $contractSchemaVersion
            Expectations = @($contractMatches)
        }
        InlayHints = ($null -ne $capabilities.inlayHintProvider)
        Formatting = ($null -ne $capabilities.documentFormattingProvider)
    }

    if ($AsJson) {
        $smokeResult | ConvertTo-Json -Depth 20
    } else {
        $smokeResult
    }
}
catch {
    $failure = $_
    Stop-ServerProcess
    $serverStderr = ""
    try {
        $serverStderr = $stderrTask.GetAwaiter().GetResult().Trim()
    }
    catch {
        # Preserve the original protocol failure if stderr collection itself
        # fails while the process is being torn down.
    }
    $message = $failure.Exception.Message
    if (-not [string]::IsNullOrWhiteSpace($serverStderr)) {
        $message += "`nslangd stderr:`n$serverStderr"
    }
    throw [System.InvalidOperationException]::new($message, $failure.Exception)
}
finally {
    Stop-ServerProcess
    $serverProcess.Dispose()
}
