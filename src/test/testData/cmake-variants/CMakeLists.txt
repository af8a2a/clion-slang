cmake_minimum_required(VERSION 3.21)
project(SlangVariantExportFixture NONE)
include("${CMAKE_CURRENT_LIST_DIR}/../../../../cmake/SlangShaderVariants.cmake")
slang_ide_add_variant(ID blue NAME "Blue $<CONFIG>" ROOT "../slang/PreprocessorVariant.slang"
    BUILD_TARGET sample_shaders ENTRY_POINT variantMain STAGE compute TARGET spirv PROFILE spirv_1_5
    DEFINES "MODE=1" "LABEL=\"quoted value\"" "EQUATION=a=b" "EMPTY" "SEMI=a;b"
    UNDEFINES REMOVED INCLUDE_PATHS "../slang")
slang_ide_add_variant(ID green NAME "Green $<CONFIG>" ROOT "../slang/PreprocessorVariant.slang"
    ENTRY_POINT variantMain STAGE compute TARGET spirv PROFILE spirv_1_5 DEFINES "MODE=2")
slang_ide_export_variants(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/slang-variants-$<CONFIG>.json")
