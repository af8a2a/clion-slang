param(
    [Parameter(Mandatory = $true)]
    [string] $SlangdPath,

    [Parameter(Mandatory = $true)]
    [string] $SlangCompilerPath,

    [Parameter(Mandatory = $true)]
    [string] $SlangGlslModulePath,

    [Parameter(Mandatory = $true)]
    [string] $SlangSource,

    [string] $LicensePath = "",
    [string] $PatchPath = "",
    [string] $M3PatchPath = "",
    [string] $FieldHoverPatchPath = "",
    [string] $FieldHoverPresentationPatchPath = "",
    [string] $DocumentLocalReferencesPatchPath = "",
    [string] $DocumentVariableHighlightsPatchPath = "",
    [string] $OutputArchive = "",
    [string] $BuildConfiguration = "RelWithDebInfo",
    [string] $BuildGenerator = "Ninja"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($PatchPath)) {
    $PatchPath = Join-Path $projectRoot "patches\slang\0001-m2a-enhanced-semantic-tokens.patch"
}
if ([string]::IsNullOrWhiteSpace($M3PatchPath)) {
    $M3PatchPath = Join-Path $projectRoot "patches\slang\0002-m3-slang-hlsl-semantic-tokens.patch"
}
if ([string]::IsNullOrWhiteSpace($FieldHoverPatchPath)) {
    $FieldHoverPatchPath = Join-Path $projectRoot "patches\slang\0003-field-layout-hover.patch"
}
if ([string]::IsNullOrWhiteSpace($FieldHoverPresentationPatchPath)) {
    $FieldHoverPresentationPatchPath = Join-Path $projectRoot "patches\slang\0004-field-hover-presentation.patch"
}
if ([string]::IsNullOrWhiteSpace($DocumentLocalReferencesPatchPath)) {
    $DocumentLocalReferencesPatchPath = Join-Path $projectRoot "patches\slang\0005-document-local-references.patch"
}
if ([string]::IsNullOrWhiteSpace($DocumentVariableHighlightsPatchPath)) {
    $DocumentVariableHighlightsPatchPath = Join-Path $projectRoot "patches\slang\0006-document-variable-highlights.patch"
}
if ([string]::IsNullOrWhiteSpace($OutputArchive)) {
    $OutputArchive = Join-Path $projectRoot ".bundled-runtime\windows-x86_64.zip"
}

function Resolve-RequiredFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description was not found: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Get-Sha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Normalize-LfText {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string] $Text
    )

    return $Text.Replace("`r`n", "`n").Replace("`r", "`n").TrimEnd([char[]] @("`n"))
}

function ConvertTo-SafeRemoteUrl {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string] $RemoteUrl
    )

    $trimmed = $RemoteUrl.Trim()
    $scpMatch = [regex]::Match($trimmed, '^[^@/:]+@(?<host>[^:]+):(?<path>.+)$')
    if ($scpMatch.Success) {
        return "ssh://$($scpMatch.Groups['host'].Value)/$($scpMatch.Groups['path'].Value.TrimStart('/'))"
    }

    $uri = $null
    if ([Uri]::TryCreate($trimmed, [UriKind]::Absolute, [ref] $uri) -and
        $uri.Scheme -in @("http", "https", "ssh", "git")) {
        $builder = [UriBuilder]::new($uri)
        $builder.UserName = ""
        $builder.Password = ""
        $builder.Query = ""
        $builder.Fragment = ""
        return $builder.Uri.AbsoluteUri.TrimEnd('/')
    }

    # Do not accidentally persist URL-style credentials if a non-standard remote
    # syntax could not be parsed by System.Uri.
    return [regex]::Replace($trimmed, '^(?<scheme>[A-Za-z][A-Za-z0-9+.-]*://)[^/@]+@', '${scheme}')
}

function Resolve-PeRva {
    param(
        [Parameter(Mandatory = $true)]
        [uint32] $Rva,

        [Parameter(Mandatory = $true)]
        [object[]] $Sections,

        [Parameter(Mandatory = $true)]
        [long] $FileLength,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    foreach ($section in $Sections) {
        $mappedSize = [Math]::Max([uint64] $section.VirtualSize, [uint64] $section.RawSize)
        $sectionStart = [uint64] $section.VirtualAddress
        $sectionEnd = $sectionStart + $mappedSize
        if ([uint64] $Rva -ge $sectionStart -and [uint64] $Rva -lt $sectionEnd) {
            $delta = [uint64] $Rva - $sectionStart
            if ($delta -ge [uint64] $section.RawSize) {
                throw "$Description points outside the PE section's file-backed data"
            }
            $offset = [uint64] $section.RawPointer + $delta
            if ($offset -ge [uint64] $FileLength) {
                throw "$Description points outside the PE file"
            }
            return [long] $offset
        }
    }
    throw "$Description RVA 0x$($Rva.ToString('x8')) is not covered by a PE section"
}

function Read-PeAsciiString {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.BinaryReader] $Reader,

        [Parameter(Mandatory = $true)]
        [long] $Offset,

        [Parameter(Mandatory = $true)]
        [long] $FileLength
    )

    if ($Offset -lt 0 -or $Offset -ge $FileLength) {
        throw "PE import name offset is outside the file"
    }
    $Reader.BaseStream.Position = $Offset
    $bytes = [System.Collections.Generic.List[byte]]::new()
    for ($index = 0; $index -lt 512; $index++) {
        if ($Reader.BaseStream.Position -ge $FileLength) {
            throw "PE import name is truncated"
        }
        $value = $Reader.ReadByte()
        if ($value -eq 0) {
            return [Text.Encoding]::ASCII.GetString($bytes.ToArray())
        }
        $bytes.Add($value)
    }
    throw "PE import name exceeds 512 bytes"
}

function Get-PeImports {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $reader = [IO.BinaryReader]::new($stream, [Text.Encoding]::ASCII, $true)
    try {
        if ($stream.Length -lt 64) {
            throw "PE file is shorter than the DOS header: $Path"
        }
        if ($reader.ReadUInt16() -ne 0x5a4d) {
            throw "File does not start with an MZ header: $Path"
        }
        $stream.Position = 0x3c
        $peOffset = [uint64] $reader.ReadUInt32()
        if ($peOffset + 24 -gt [uint64] $stream.Length) {
            throw "PE header is outside the file: $Path"
        }
        $stream.Position = [long] $peOffset
        if ($reader.ReadUInt32() -ne 0x00004550) {
            throw "File does not contain a PE signature: $Path"
        }
        $machine = $reader.ReadUInt16()
        if ($machine -ne 0x8664) {
            throw "PE machine for '$Path' is 0x$($machine.ToString('x4')); AMD64 (0x8664) is required"
        }
        $sectionCount = [int] $reader.ReadUInt16()
        if ($sectionCount -le 0 -or $sectionCount -gt 96) {
            throw "PE section count is invalid: $sectionCount"
        }
        [void] $reader.ReadUInt32() # timestamp
        [void] $reader.ReadUInt32() # symbol table pointer
        [void] $reader.ReadUInt32() # symbol count
        $optionalHeaderSize = [int] $reader.ReadUInt16()
        [void] $reader.ReadUInt16() # characteristics
        $optionalHeaderOffset = $stream.Position
        if ($optionalHeaderSize -lt 128 -or
            $optionalHeaderOffset + $optionalHeaderSize + (40L * $sectionCount) -gt $stream.Length) {
            throw "PE optional header or section table is truncated: $Path"
        }
        if ($reader.ReadUInt16() -ne 0x020b) {
            throw "PE optional header for '$Path' is not PE32+"
        }

        $stream.Position = $optionalHeaderOffset + 108
        $dataDirectoryCount = [uint32] $reader.ReadUInt32()
        if ($dataDirectoryCount -lt 2) {
            throw "PE import directory is unavailable: $Path"
        }
        $stream.Position = $optionalHeaderOffset + 120
        $importRva = $reader.ReadUInt32()
        $importSize = $reader.ReadUInt32()

        $sections = [System.Collections.Generic.List[object]]::new()
        $sectionTableOffset = $optionalHeaderOffset + $optionalHeaderSize
        for ($index = 0; $index -lt $sectionCount; $index++) {
            $stream.Position = $sectionTableOffset + (40L * $index) + 8
            $virtualSize = $reader.ReadUInt32()
            $virtualAddress = $reader.ReadUInt32()
            $rawSize = $reader.ReadUInt32()
            $rawPointer = $reader.ReadUInt32()
            $sections.Add([pscustomobject] @{
                VirtualSize = $virtualSize
                VirtualAddress = $virtualAddress
                RawSize = $rawSize
                RawPointer = $rawPointer
            })
        }

        $imports = [System.Collections.Generic.List[string]]::new()
        if ($importRva -eq 0 -and $importSize -eq 0) {
            return [string[]] @()
        }
        if ($importRva -eq 0 -or $importSize -lt 20) {
            throw "PE import directory is malformed: $Path"
        }
        $descriptorOffset = Resolve-PeRva -Rva $importRva -Sections $sections.ToArray() `
            -FileLength $stream.Length -Description "PE import directory"
        $terminated = $false
        for ($index = 0; $index -lt 4096; $index++) {
            if ($descriptorOffset + 20 -gt $stream.Length) {
                throw "PE import descriptor table is truncated: $Path"
            }
            $stream.Position = $descriptorOffset
            $originalFirstThunk = $reader.ReadUInt32()
            $timeDateStamp = $reader.ReadUInt32()
            $forwarderChain = $reader.ReadUInt32()
            $nameRva = $reader.ReadUInt32()
            $firstThunk = $reader.ReadUInt32()
            if (($originalFirstThunk -bor $timeDateStamp -bor $forwarderChain -bor $nameRva -bor $firstThunk) -eq 0) {
                $terminated = $true
                break
            }
            if ($nameRva -eq 0) {
                throw "PE import descriptor has no DLL name: $Path"
            }
            $nameOffset = Resolve-PeRva -Rva $nameRva -Sections $sections.ToArray() `
                -FileLength $stream.Length -Description "PE import name"
            $imports.Add((Read-PeAsciiString -Reader $reader -Offset $nameOffset -FileLength $stream.Length))
            $descriptorOffset += 20
        }
        if (-not $terminated) {
            throw "PE import descriptor table is not terminated: $Path"
        }
        return $imports.ToArray()
    }
    finally {
        $reader.Dispose()
        $stream.Dispose()
    }
}

function Assert-StaticCrtAmd64Pe {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    $imports = @(Get-PeImports -Path $Path)
    $dynamicCrtImports = @($imports | Where-Object {
        $_ -match '^(?i:(msvcp|msvcr|vcruntime|concrt)[^/\\]*\.dll|ucrtbase\.dll|api-ms-win-crt-[^/\\]*\.dll)$'
    })
    if ($dynamicCrtImports.Count -ne 0) {
        throw "$Description imports the dynamic MSVC/UCRT runtime ($($dynamicCrtImports -join ', ')). Rebuild Slang with CMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded (/MT)."
    }
    return [string[]] $imports
}

function Assert-GlslModuleCompilerTimestamp {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ModulePath,

        [Parameter(Mandatory = $true)]
        [string] $CompilerPath
    )

    $moduleStream = [IO.File]::Open($ModulePath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $moduleReader = [IO.BinaryReader]::new($moduleStream, [Text.Encoding]::ASCII, $true)
    try {
        if ($moduleStream.Length -lt 8) {
            throw "slang-glsl-module.bin is shorter than its compiler timestamp header"
        }
        $recordedCompilerUnixTime = $moduleReader.ReadInt64()
    }
    finally {
        $moduleReader.Dispose()
        $moduleStream.Dispose()
    }

    $compilerWriteTime = [DateTimeOffset]::new((Get-Item -LiteralPath $CompilerPath).LastWriteTimeUtc)
    $compilerUnixTime = $compilerWriteTime.ToUnixTimeSeconds()
    if ($recordedCompilerUnixTime -ne $compilerUnixTime) {
        throw "slang-glsl-module.bin records compiler mtime $recordedCompilerUnixTime, but slang-compiler.dll has Unix mtime $compilerUnixTime. Run the final slangd smoke test to refresh slang-glsl-module.bin, then package again."
    }
}

function Invoke-SlangdSmokeContract {
    param(
        [Parameter(Mandatory = $true)]
        [string] $SlangdPath,

        [Parameter(Mandatory = $true)]
        [ValidateSet("m3", "enhanced", "stock")]
        [string] $SemanticContract,

        [Parameter(Mandatory = $true)]
        [string] $SmokeScriptPath
    )

    $powerShellPath = (Get-Process -Id $PID).Path
    if (-not (Test-Path -LiteralPath $powerShellPath -PathType Leaf)) {
        throw "Cannot locate the current PowerShell executable for the slangd smoke test"
    }

    # slangd-lsp-smoke.ps1 deliberately calls exit. Invoking the current host as
    # an executable isolates that exit code from this packaging process.
    $output = & $powerShellPath `
        -NoLogo `
        -NoProfile `
        -NonInteractive `
        -ExecutionPolicy Bypass `
        -File $SmokeScriptPath `
        -Slangd $SlangdPath `
        -SemanticContract $SemanticContract 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "slangd $SemanticContract LSP contract smoke failed with exit code ${exitCode}:`n$($output -join "`n")"
    }
    Write-Host "slangd LSP contract smoke passed: $SemanticContract"
}

function Invoke-SlangGit {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments,

        [switch] $PreserveOutput
    )

    $output = & git -c core.safecrlf=false -C $script:resolvedSlangSource @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed for Slang source '$script:resolvedSlangSource':`n$($output -join "`n")"
    }
    $joined = $output -join "`n"
    if ($PreserveOutput) {
        return $joined
    }
    return $joined.Trim()
}

function Invoke-GitRepository {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Repository,

        [Parameter(Mandatory = $true)]
        [string[]] $Arguments,

        [switch] $PreserveOutput
    )

    $output = & git -c core.safecrlf=false -C $Repository @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed for '$Repository':`n$($output -join "`n")"
    }
    $joined = $output -join "`n"
    if ($PreserveOutput) {
        return $joined
    }
    return $joined.Trim()
}

function ConvertTo-JsonString {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string] $Value
    )

    $builder = [System.Text.StringBuilder]::new()
    foreach ($character in $Value.ToCharArray()) {
        $code = [int] $character
        switch ($code) {
            8 { [void] $builder.Append('\b'); break }
            9 { [void] $builder.Append('\t'); break }
            10 { [void] $builder.Append('\n'); break }
            12 { [void] $builder.Append('\f'); break }
            13 { [void] $builder.Append('\r'); break }
            34 { [void] $builder.Append('\"'); break }
            92 { [void] $builder.Append('\\'); break }
            default {
                if ($code -lt 32) {
                    [void] $builder.AppendFormat('\u{0:x4}', $code)
                }
                else {
                    [void] $builder.Append($character)
                }
            }
        }
    }
    return '"' + $builder.ToString() + '"'
}

# ZipArchive's Deflate and even its NoCompression behavior differs between
# .NET Framework and modern .NET. This small STORE-only writer keeps the
# release artifact byte-for-byte reproducible across PowerShell runtimes.
if ($null -eq ("ClionSlangDeterministicZip" -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

public static class ClionSlangDeterministicZip
{
    private sealed class Entry
    {
        public byte[] Name;
        public uint Crc;
        public uint Size;
        public uint Offset;
    }

    private static readonly uint[] CrcTable = CreateCrcTable();

    public static void Create(
        string outputPath,
        string[] entryNames,
        string[] filePaths,
        byte[] manifestBytes)
    {
        if (entryNames == null || filePaths == null || entryNames.Length != filePaths.Length)
            throw new ArgumentException("ZIP entry names and paths must have matching lengths.");
        if (entryNames.Length > UInt16.MaxValue)
            throw new ArgumentException("Too many ZIP entries.");

        var entries = new List<Entry>(entryNames.Length);
        using (var output = new FileStream(outputPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
        using (var writer = new BinaryWriter(output, new UTF8Encoding(false), true))
        {
            for (int index = 0; index < entryNames.Length; index++)
            {
                byte[] name = Encoding.UTF8.GetBytes(entryNames[index]);
                if (name.Length > UInt16.MaxValue)
                    throw new ArgumentException("ZIP entry name is too long: " + entryNames[index]);

                byte[] bytes = String.IsNullOrEmpty(filePaths[index]) ? manifestBytes : null;
                long sourceLength = bytes == null ? new FileInfo(filePaths[index]).Length : bytes.LongLength;
                if (sourceLength > UInt32.MaxValue)
                    throw new ArgumentException("ZIP64 is not supported: " + entryNames[index]);

                uint size = (uint)sourceLength;
                uint crc = bytes == null ? ComputeFileCrc(filePaths[index]) : ComputeCrc(bytes);
                uint offset = checked((uint)output.Position);

                writer.Write(0x04034b50u);
                writer.Write((ushort)20);
                writer.Write((ushort)0x0800);
                writer.Write((ushort)0);
                writer.Write((ushort)0);
                writer.Write((ushort)33);
                writer.Write(crc);
                writer.Write(size);
                writer.Write(size);
                writer.Write((ushort)name.Length);
                writer.Write((ushort)0);
                writer.Write(name);

                if (bytes == null)
                {
                    using (var input = File.OpenRead(filePaths[index]))
                        input.CopyTo(output);
                }
                else
                {
                    writer.Write(bytes);
                }

                entries.Add(new Entry { Name = name, Crc = crc, Size = size, Offset = offset });
            }

            uint centralOffset = checked((uint)output.Position);
            foreach (Entry entry in entries)
            {
                writer.Write(0x02014b50u);
                writer.Write((ushort)20);
                writer.Write((ushort)20);
                writer.Write((ushort)0x0800);
                writer.Write((ushort)0);
                writer.Write((ushort)0);
                writer.Write((ushort)33);
                writer.Write(entry.Crc);
                writer.Write(entry.Size);
                writer.Write(entry.Size);
                writer.Write((ushort)entry.Name.Length);
                writer.Write((ushort)0);
                writer.Write((ushort)0);
                writer.Write((ushort)0);
                writer.Write((ushort)0);
                writer.Write(0u);
                writer.Write(entry.Offset);
                writer.Write(entry.Name);
            }

            uint centralSize = checked((uint)output.Position - centralOffset);
            writer.Write(0x06054b50u);
            writer.Write((ushort)0);
            writer.Write((ushort)0);
            writer.Write((ushort)entries.Count);
            writer.Write((ushort)entries.Count);
            writer.Write(centralSize);
            writer.Write(centralOffset);
            writer.Write((ushort)0);
        }
    }

    private static uint ComputeFileCrc(string path)
    {
        using (var stream = File.OpenRead(path))
        {
            uint crc = UInt32.MaxValue;
            var buffer = new byte[81920];
            int count;
            while ((count = stream.Read(buffer, 0, buffer.Length)) != 0)
                crc = UpdateCrc(crc, buffer, count);
            return ~crc;
        }
    }

    private static uint ComputeCrc(byte[] bytes)
    {
        return ~UpdateCrc(UInt32.MaxValue, bytes, bytes.Length);
    }

    private static uint UpdateCrc(uint crc, byte[] bytes, int count)
    {
        for (int index = 0; index < count; index++)
            crc = CrcTable[(crc ^ bytes[index]) & 0xff] ^ (crc >> 8);
        return crc;
    }

    private static uint[] CreateCrcTable()
    {
        var table = new uint[256];
        for (uint value = 0; value < table.Length; value++)
        {
            uint crc = value;
            for (int bit = 0; bit < 8; bit++)
                crc = (crc & 1) == 0 ? crc >> 1 : 0xedb88320u ^ (crc >> 1);
            table[value] = crc;
        }
        return table;
    }
}
'@
}

$resolvedSlangd = Resolve-RequiredFile -Path $SlangdPath -Description "slangd executable"
$resolvedCompiler = Resolve-RequiredFile -Path $SlangCompilerPath -Description "slang compiler library"
$resolvedGlslModule = Resolve-RequiredFile -Path $SlangGlslModulePath -Description "Slang GLSL core module"
if (-not (Test-Path -LiteralPath $SlangSource -PathType Container)) {
    throw "Slang source directory was not found: $SlangSource"
}
$resolvedSlangSource = (Resolve-Path -LiteralPath $SlangSource).Path

if ([string]::IsNullOrWhiteSpace($LicensePath)) {
    $LicensePath = Join-Path $resolvedSlangSource "LICENSE"
}
$resolvedLicense = Resolve-RequiredFile -Path $LicensePath -Description "Slang license"
$resolvedM2aPatch = Resolve-RequiredFile -Path $PatchPath -Description "M2a Slang patch"
$resolvedM3Patch = Resolve-RequiredFile -Path $M3PatchPath -Description "M3 Slang patch"
$resolvedFieldHoverPatch = Resolve-RequiredFile `
    -Path $FieldHoverPatchPath `
    -Description "field layout hover Slang patch"
$resolvedFieldHoverPresentationPatch = Resolve-RequiredFile `
    -Path $FieldHoverPresentationPatchPath `
    -Description "field hover presentation Slang patch"
$resolvedDocumentLocalReferencesPatch = Resolve-RequiredFile `
    -Path $DocumentLocalReferencesPatchPath `
    -Description "document-local references Slang patch"
$resolvedDocumentVariableHighlightsPatch = Resolve-RequiredFile `
    -Path $DocumentVariableHighlightsPatchPath `
    -Description "document variable highlights Slang patch"
$resolvedMinizLicense = Resolve-RequiredFile `
    -Path (Join-Path $resolvedSlangSource "external\miniz\LICENSE") `
    -Description "miniz license"
$resolvedLz4DistributionLicense = Resolve-RequiredFile `
    -Path (Join-Path $resolvedSlangSource "external\lz4\LICENSE") `
    -Description "LZ4 distribution license notice"
$resolvedLz4LibraryLicense = Resolve-RequiredFile `
    -Path (Join-Path $resolvedSlangSource "external\lz4\lib\LICENSE") `
    -Description "LZ4 library BSD-2-Clause license"
$resolvedUnorderedDenseLicense = Resolve-RequiredFile `
    -Path (Join-Path $resolvedSlangSource "external\unordered_dense\LICENSE") `
    -Description "unordered_dense license"

$gitCommand = Get-Command -Name git -CommandType Application -ErrorAction Stop |
    Select-Object -First 1
if ($null -eq $gitCommand) {
    throw "git is required to record the Slang source revision"
}

$sourceCommit = Invoke-SlangGit -Arguments @("rev-parse", "HEAD")
$trackedDiff = Normalize-LfText -Text (Invoke-SlangGit -Arguments @(
    "diff",
    "--binary",
    "--no-ext-diff",
    "--ignore-submodules=all",
    "HEAD",
    "--"
) -PreserveOutput)

# Reconstruct the expected publisher tree from the clean source revision. The
# patches overlap by design, so concatenating their text cannot represent the
# final HEAD-to-working-tree diff. A shared, no-checkout clone is cheap and lets
# git apply each reviewed layer in order before producing the canonical diff.
$systemTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$patchReplayDirectory = Join-Path `
    $systemTempRoot `
    ("clion-slang-patch-replay-" + [Guid]::NewGuid().ToString("N"))
$expectedTrackedDiff = $null
try {
    $cloneOutput = & git -c core.safecrlf=false clone `
        --quiet `
        --shared `
        --no-checkout `
        -- `
        $resolvedSlangSource `
        $patchReplayDirectory 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to create temporary shared Slang clone:`n$($cloneOutput -join "`n")"
    }

    [void] (Invoke-GitRepository `
        -Repository $patchReplayDirectory `
        -Arguments @("checkout", "--quiet", "--detach", $sourceCommit))
    foreach ($patch in @(
        $resolvedM2aPatch,
        $resolvedM3Patch,
        $resolvedFieldHoverPatch,
        $resolvedFieldHoverPresentationPatch,
        $resolvedDocumentLocalReferencesPatch,
        $resolvedDocumentVariableHighlightsPatch
    )) {
        [void] (Invoke-GitRepository `
            -Repository $patchReplayDirectory `
            -Arguments @("apply", "--check", $patch))
        [void] (Invoke-GitRepository `
            -Repository $patchReplayDirectory `
            -Arguments @("apply", $patch))
    }

    $expectedTrackedDiff = Normalize-LfText -Text (Invoke-GitRepository `
        -Repository $patchReplayDirectory `
        -Arguments @(
            "diff",
            "--binary",
            "--no-ext-diff",
            "--ignore-submodules=all",
            "HEAD",
            "--"
        ) `
        -PreserveOutput)
}
finally {
    if (Test-Path -LiteralPath $patchReplayDirectory -PathType Container) {
        $resolvedReplayDirectory = [IO.Path]::GetFullPath($patchReplayDirectory)
        $resolvedReplayParent = [IO.Path]::GetFullPath(
            (Split-Path -Parent $resolvedReplayDirectory)
        ).TrimEnd('\', '/')
        if ($resolvedReplayParent -cne $systemTempRoot -or
            -not ([IO.Path]::GetFileName($resolvedReplayDirectory)).StartsWith(
                "clion-slang-patch-replay-",
                [StringComparison]::Ordinal
            )) {
            throw "Refusing to remove unexpected patch replay directory: $resolvedReplayDirectory"
        }
        [IO.Directory]::Delete($resolvedReplayDirectory, $true)
    }
}

if ($trackedDiff -cne $expectedTrackedDiff) {
    $changedPaths = Invoke-SlangGit -Arguments @(
        "diff",
        "--name-status",
        "--no-ext-diff",
        "--ignore-submodules=all",
        "HEAD",
        "--"
    )
    throw "The tracked Slang source diff does not exactly match replaying the recorded M2a, M3, field-layout, field-presentation, document-local references, and document-variable highlights patches in order. Submodule worktree state is ignored, but no additional tracked superproject changes are allowed.`nTracked changes:`n$changedPaths"
}
$sourceDescribe = Invoke-SlangGit -Arguments @("describe", "--tags", "--always", "--dirty")
$sourceRepository = ConvertTo-SafeRemoteUrl -RemoteUrl (
    Invoke-SlangGit -Arguments @("remote", "get-url", "origin")
)

$slangdImports = Assert-StaticCrtAmd64Pe -Path $resolvedSlangd -Description "slangd.exe"
$compilerImports = Assert-StaticCrtAmd64Pe -Path $resolvedCompiler -Description "slang-compiler.dll"
if ($slangdImports -notcontains "slang-compiler.dll") {
    throw "slangd.exe does not import slang-compiler.dll; the inputs are not a supported dynamic compiler pair"
}
$resolvedSmokeScript = Resolve-RequiredFile `
    -Path (Join-Path $projectRoot "scripts\slangd-lsp-smoke.ps1") `
    -Description "slangd LSP smoke script"
Invoke-SlangdSmokeContract `
    -SlangdPath $resolvedSlangd `
    -SemanticContract m3 `
    -SmokeScriptPath $resolvedSmokeScript
Invoke-SlangdSmokeContract `
    -SlangdPath $resolvedSlangd `
    -SemanticContract enhanced `
    -SmokeScriptPath $resolvedSmokeScript
Invoke-SlangdSmokeContract `
    -SlangdPath $resolvedSlangd `
    -SemanticContract stock `
    -SmokeScriptPath $resolvedSmokeScript
Assert-GlslModuleCompilerTimestamp -ModulePath $resolvedGlslModule -CompilerPath $resolvedCompiler

$payloadFiles = [ordered]@{
    "0001-m2a-enhanced-semantic-tokens.patch" = $resolvedM2aPatch
    "0002-m3-slang-hlsl-semantic-tokens.patch" = $resolvedM3Patch
    "0003-field-layout-hover.patch" = $resolvedFieldHoverPatch
    "0004-field-hover-presentation.patch" = $resolvedFieldHoverPresentationPatch
    "0005-document-local-references.patch" = $resolvedDocumentLocalReferencesPatch
    "0006-document-variable-highlights.patch" = $resolvedDocumentVariableHighlightsPatch
    "LICENSE-slang.txt" = $resolvedLicense
    "LICENSES/lz4-distribution.txt" = $resolvedLz4DistributionLicense
    "LICENSES/lz4-lib-BSD-2-Clause.txt" = $resolvedLz4LibraryLicense
    "LICENSES/miniz-MIT.txt" = $resolvedMinizLicense
    "LICENSES/unordered_dense-MIT.txt" = $resolvedUnorderedDenseLicense
    "slang-compiler.dll" = $resolvedCompiler
    "slang-glsl-module.bin" = $resolvedGlslModule
    "slangd.exe" = $resolvedSlangd
}
$fileHashes = [ordered]@{}
foreach ($entryName in $payloadFiles.Keys) {
    $fileHashes[$entryName] = Get-Sha256 -Path $payloadFiles[$entryName]
}

$manifestLines = @(
    '{',
    '  "schemaVersion": 1,',
    '  "profile": "clion-slang-m3",',
    '  "source": {',
    ('    "repository": {0},' -f (ConvertTo-JsonString $sourceRepository)),
    ('    "commit": {0},' -f (ConvertTo-JsonString $sourceCommit)),
    ('    "describe": {0}' -f (ConvertTo-JsonString $sourceDescribe)),
    '  },',
    '  "build": {',
    '    "platform": "windows-x64",',
    ('    "configuration": {0},' -f (ConvertTo-JsonString $BuildConfiguration)),
    ('    "generator": {0}' -f (ConvertTo-JsonString $BuildGenerator)),
    '  },',
    '  "protocol": {',
    '    "major": 1,',
    '    "minor": 4,',
    '    "features": ["semanticTokens.m2a", "semanticTokens.m3", "hover.fieldLayout.natural", "references.documentLocal", "documentHighlight.documentLocal"]',
    '  },',
    '  "files": {',
    ('    "0001-m2a-enhanced-semantic-tokens.patch": {0},' -f (ConvertTo-JsonString $fileHashes['0001-m2a-enhanced-semantic-tokens.patch'])),
    ('    "0002-m3-slang-hlsl-semantic-tokens.patch": {0},' -f (ConvertTo-JsonString $fileHashes['0002-m3-slang-hlsl-semantic-tokens.patch'])),
    ('    "0003-field-layout-hover.patch": {0},' -f (ConvertTo-JsonString $fileHashes['0003-field-layout-hover.patch'])),
    ('    "0004-field-hover-presentation.patch": {0},' -f (ConvertTo-JsonString $fileHashes['0004-field-hover-presentation.patch'])),
    ('    "0005-document-local-references.patch": {0},' -f (ConvertTo-JsonString $fileHashes['0005-document-local-references.patch'])),
    ('    "0006-document-variable-highlights.patch": {0},' -f (ConvertTo-JsonString $fileHashes['0006-document-variable-highlights.patch'])),
    ('    "LICENSE-slang.txt": {0},' -f (ConvertTo-JsonString $fileHashes['LICENSE-slang.txt'])),
    ('    "LICENSES/lz4-distribution.txt": {0},' -f (ConvertTo-JsonString $fileHashes['LICENSES/lz4-distribution.txt'])),
    ('    "LICENSES/lz4-lib-BSD-2-Clause.txt": {0},' -f (ConvertTo-JsonString $fileHashes['LICENSES/lz4-lib-BSD-2-Clause.txt'])),
    ('    "LICENSES/miniz-MIT.txt": {0},' -f (ConvertTo-JsonString $fileHashes['LICENSES/miniz-MIT.txt'])),
    ('    "LICENSES/unordered_dense-MIT.txt": {0},' -f (ConvertTo-JsonString $fileHashes['LICENSES/unordered_dense-MIT.txt'])),
    ('    "slang-compiler.dll": {0},' -f (ConvertTo-JsonString $fileHashes['slang-compiler.dll'])),
    ('    "slang-glsl-module.bin": {0},' -f (ConvertTo-JsonString $fileHashes['slang-glsl-module.bin'])),
    ('    "slangd.exe": {0}' -f (ConvertTo-JsonString $fileHashes['slangd.exe'])),
    '  }',
    '}'
)
$manifestJson = ($manifestLines -join "`n") + "`n"
$manifestBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($manifestJson)

if ([System.IO.Path]::IsPathRooted($OutputArchive)) {
    $resolvedOutput = [System.IO.Path]::GetFullPath($OutputArchive)
}
else {
    $resolvedOutput = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputArchive))
}
$outputDirectory = Split-Path -Parent $resolvedOutput
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null

$temporaryArchive = "$resolvedOutput.tmp-$([Guid]::NewGuid().ToString('N'))"

try {
    $entryNames = [string[]] @(
        "0001-m2a-enhanced-semantic-tokens.patch",
        "0002-m3-slang-hlsl-semantic-tokens.patch",
        "0003-field-layout-hover.patch",
        "0004-field-hover-presentation.patch",
        "0005-document-local-references.patch",
        "0006-document-variable-highlights.patch",
        "LICENSE-slang.txt",
        "LICENSES/lz4-distribution.txt",
        "LICENSES/lz4-lib-BSD-2-Clause.txt",
        "LICENSES/miniz-MIT.txt",
        "LICENSES/unordered_dense-MIT.txt",
        "manifest.json",
        "slang-compiler.dll",
        "slang-glsl-module.bin",
        "slangd.exe"
    )
    $filePaths = [string[]] @($entryNames | ForEach-Object {
        if ($_ -eq "manifest.json") { $null } else { $payloadFiles[$_] }
    })
    [ClionSlangDeterministicZip]::Create($temporaryArchive, $entryNames, $filePaths, $manifestBytes)

    if (Test-Path -LiteralPath $resolvedOutput -PathType Leaf) {
        Remove-Item -LiteralPath $resolvedOutput -Force
    }
    Move-Item -LiteralPath $temporaryArchive -Destination $resolvedOutput
}
finally {
    if (Test-Path -LiteralPath $temporaryArchive -PathType Leaf) {
        Remove-Item -LiteralPath $temporaryArchive -Force
    }
}

$archiveHash = Get-Sha256 -Path $resolvedOutput
Write-Host "Bundled slangd archive: $resolvedOutput"
Write-Host "SHA-256: $archiveHash"
