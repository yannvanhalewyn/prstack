#!/bin/bash
# Render Mermaid diagrams to SVG with PrStack documentation theme
#
# Usage:
#   ./scripts/render-mermaid.sh <input.mmd> [output.svg]
#   ./scripts/render-mermaid.sh diagrams/feature-base.mmd
#   ./scripts/render-mermaid.sh diagrams/feature-base.mmd public/diagrams/feature-base.svg

set -e

if [ $# -eq 0 ]; then
    echo "Usage: $0 <input.mmd> [output.svg]"
    echo ""
    echo "Renders a Mermaid diagram to SVG using the PrStack documentation theme."
    echo ""
    echo "Examples:"
    echo "  $0 diagrams/example.mmd"
    echo "  $0 diagrams/example.mmd public/example.svg"
    exit 1
fi

INPUT="$1"
OUTPUT="${2:-${INPUT%.mmd}.svg}"

if [ ! -f "$INPUT" ]; then
    echo "Error: Input file '$INPUT' not found"
    exit 1
fi

echo "Rendering Mermaid diagram..."
echo "  Input:  $INPUT"
echo "  Output: $OUTPUT"
echo ""

bunx mmdc \
    -i "$INPUT" \
    -o "$OUTPUT" \
    -t dark \
    -b '#0a0a14' \
    -C mermaid-config.json

echo ""
echo "✓ Successfully rendered diagram to $OUTPUT"
