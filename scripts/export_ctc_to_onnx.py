#!/usr/bin/env python3
"""
CTC (48px_ctc) .ckpt -> .onnx conversion script.

Converts manga-image-translator's ocr-ctc.ckpt to ONNX format for Android ONNX Runtime.

Architecture: ResNet(3,320) + TransformerEncoder(320,8,1280) x3 + CTC head
Input:  [batch, 3, 48, width]  (dynamic width)
Output: char_logits [batch, seqLen, dictSize]
        color_values [batch, seqLen, 6]

Usage:
    python scripts/export_ctc_to_onnx.py

Requires:
    pip install torch onnx onnxscript einops
"""

import sys
import math
from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch import Tensor
from typing import List, Tuple, Optional

# Paths
SCRIPT_DIR = Path(__file__).parent
REPO_ROOT = SCRIPT_DIR.parent
MODEL_DIR = REPO_ROOT / ".reference" / "models"
OUTPUT_DIR = REPO_ROOT / "tools" / "ctc_onnx"
CHECKPOINT_PATH = MODEL_DIR / "ocr-ctc.ckpt"
ALPHABET_PATH = MODEL_DIR / "alphabet-all-v5.txt"


# ============================================================
# Model architecture (copied from manga-image-translator)
# ============================================================

class PositionalEncoding(nn.Module):
    def __init__(self, d_model, dropout=0.1, max_len=5000):
        super().__init__()
        self.dropout = nn.Dropout(p=dropout)
        pe = torch.zeros(max_len, d_model)
        position = torch.arange(0, max_len, dtype=torch.float).unsqueeze(1)
        div_term = torch.exp(torch.arange(0, d_model, 2).float() * (-math.log(10000.0) / d_model))
        pe[:, 0::2] = torch.sin(position * div_term)
        pe[:, 1::2] = torch.cos(position * div_term)
        pe = pe.unsqueeze(0)
        self.register_buffer('pe', pe)

    def forward(self, x, offset=0):
        x = x + self.pe[:, offset:offset + x.size(1), :]
        return x


class CustomTransformerEncoderLayer(nn.Module):
    def __init__(self, d_model, nhead, dim_feedforward=2048, dropout=0.1,
                 activation="gelu", layer_norm_eps=1e-5, batch_first=False,
                 norm_first=False):
        super().__init__()
        self.self_attn = nn.MultiheadAttention(d_model, nhead, dropout=dropout, batch_first=batch_first)
        self.linear1 = nn.Linear(d_model, dim_feedforward)
        self.dropout = nn.Dropout(dropout)
        self.linear2 = nn.Linear(dim_feedforward, d_model)
        self.norm_first = norm_first
        self.norm1 = nn.LayerNorm(d_model, eps=layer_norm_eps)
        self.norm2 = nn.LayerNorm(d_model, eps=layer_norm_eps)
        self.dropout1 = nn.Dropout(dropout)
        self.dropout2 = nn.Dropout(dropout)
        self.pe = PositionalEncoding(d_model, max_len=2048)
        self.activation = F.gelu

    def forward(self, src: Tensor, src_mask=None, src_key_padding_mask=None, is_causal=None) -> Tensor:
        x = src
        if self.norm_first:
            x = x + self._sa_block(self.norm1(x), src_mask, src_key_padding_mask)
            x = x + self._ff_block(self.norm2(x))
        else:
            x = self.norm1(x + self._sa_block(x, src_mask, src_key_padding_mask))
            x = self.norm2(x + self._ff_block(x))
        return x

    def _sa_block(self, x: Tensor, attn_mask, key_padding_mask) -> Tensor:
        x = self.self_attn(self.pe(x), self.pe(x), x,
                           attn_mask=attn_mask,
                           key_padding_mask=key_padding_mask,
                           need_weights=False)[0]
        return self.dropout1(x)

    def _ff_block(self, x: Tensor) -> Tensor:
        x = self.linear2(self.dropout(self.activation(self.linear1(x))))
        return self.dropout2(x)


class BasicBlock(nn.Module):
    expansion = 1

    def __init__(self, inplanes, planes, stride=1, downsample=None):
        super().__init__()
        self.bn1 = nn.BatchNorm2d(inplanes)
        self.conv1 = nn.Conv2d(inplanes, planes, kernel_size=3, stride=stride, padding=1, bias=False)
        self.bn2 = nn.BatchNorm2d(planes)
        self.conv2 = nn.Conv2d(planes, planes, kernel_size=3, stride=1, padding=1, bias=False)
        self.downsample = downsample
        self.stride = stride

    def forward(self, x):
        residual = x
        out = F.relu(self.bn1(x))
        out = self.conv1(out)
        out = F.relu(self.bn2(out))
        out = self.conv2(out)
        if self.downsample is not None:
            residual = self.downsample(residual)
        return out + residual


class ResNet(nn.Module):
    def __init__(self, input_channel, output_channel, block, layers):
        super().__init__()
        self.output_channel_block = [int(output_channel / 4), int(output_channel / 2), output_channel, output_channel]
        self.inplanes = int(output_channel / 8)

        self.conv0_1 = nn.Conv2d(input_channel, int(output_channel / 8), kernel_size=3, stride=1, padding=1, bias=False)
        self.bn0_1 = nn.BatchNorm2d(int(output_channel / 8))
        self.conv0_2 = nn.Conv2d(int(output_channel / 8), self.inplanes, kernel_size=3, stride=1, padding=1, bias=False)

        self.maxpool1 = nn.AvgPool2d(kernel_size=2, stride=2, padding=0)
        self.layer1 = self._make_layer(block, self.output_channel_block[0], layers[0])
        self.bn1 = nn.BatchNorm2d(self.output_channel_block[0])
        self.conv1 = nn.Conv2d(self.output_channel_block[0], self.output_channel_block[0], kernel_size=3, stride=1, padding=1, bias=False)

        self.maxpool2 = nn.AvgPool2d(kernel_size=2, stride=2, padding=0)
        self.layer2 = self._make_layer(block, self.output_channel_block[1], layers[1], stride=1)
        self.bn2 = nn.BatchNorm2d(self.output_channel_block[1])
        self.conv2 = nn.Conv2d(self.output_channel_block[1], self.output_channel_block[1], kernel_size=3, stride=1, padding=1, bias=False)

        self.maxpool3 = nn.AvgPool2d(kernel_size=2, stride=(2, 1), padding=(0, 1))
        self.layer3 = self._make_layer(block, self.output_channel_block[2], layers[2], stride=1)
        self.bn3 = nn.BatchNorm2d(self.output_channel_block[2])
        self.conv3 = nn.Conv2d(self.output_channel_block[2], self.output_channel_block[2], kernel_size=3, stride=1, padding=1, bias=False)

        self.layer4 = self._make_layer(block, self.output_channel_block[3], layers[3], stride=1)
        self.bn4_1 = nn.BatchNorm2d(self.output_channel_block[3])
        self.conv4_1 = nn.Conv2d(self.output_channel_block[3], self.output_channel_block[3], kernel_size=3, stride=(2, 1), padding=(1, 1), bias=False)
        self.bn4_2 = nn.BatchNorm2d(self.output_channel_block[3])
        self.conv4_2 = nn.Conv2d(self.output_channel_block[3], self.output_channel_block[3], kernel_size=3, stride=1, padding=0, bias=False)
        self.bn4_3 = nn.BatchNorm2d(self.output_channel_block[3])

    def _make_layer(self, block, planes, blocks, stride=1):
        downsample = None
        if stride != 1 or self.inplanes != planes * block.expansion:
            downsample = nn.Sequential(
                nn.BatchNorm2d(self.inplanes),
                nn.Conv2d(self.inplanes, planes * block.expansion, kernel_size=1, stride=stride, bias=False),
            )
        layers = [block(self.inplanes, planes, stride, downsample)]
        self.inplanes = planes * block.expansion
        for _ in range(1, blocks):
            layers.append(block(self.inplanes, planes))
        return nn.Sequential(*layers)

    def forward(self, x):
        x = self.conv0_1(x)
        x = self.bn0_1(x)
        x = F.relu(x)
        x = self.conv0_2(x)

        x = self.maxpool1(x)
        x = self.layer1(x)
        x = self.bn1(x)
        x = F.relu(x)
        x = self.conv1(x)

        x = self.maxpool2(x)
        x = self.layer2(x)
        x = self.bn2(x)
        x = F.relu(x)
        x = self.conv2(x)

        x = self.maxpool3(x)
        x = self.layer3(x)
        x = self.bn3(x)
        x = F.relu(x)
        x = self.conv3(x)

        x = self.layer4(x)
        x = self.bn4_1(x)
        x = F.relu(x)
        x = self.conv4_1(x)
        x = self.bn4_2(x)
        x = F.relu(x)
        x = self.conv4_2(x)
        x = self.bn4_3(x)
        return x


class ResNet_FeatureExtractor(nn.Module):
    def __init__(self, input_channel, output_channel=128):
        super().__init__()
        self.ConvNet = ResNet(input_channel, output_channel, BasicBlock, [4, 6, 8, 6, 3])

    def forward(self, input):
        return self.ConvNet(input)


class OCR(nn.Module):
    def __init__(self, dictionary, max_len):
        super().__init__()
        self.max_len = max_len
        self.dictionary = dictionary
        self.dict_size = len(dictionary)
        self.backbone = ResNet_FeatureExtractor(3, 320)
        enc = CustomTransformerEncoderLayer(320, 8, 320 * 4, dropout=0.05, batch_first=True, norm_first=True)
        self.encoders = nn.TransformerEncoder(enc, 3)
        self.char_pred_norm = nn.Sequential(nn.LayerNorm(320), nn.Dropout(0.1), nn.GELU())
        self.char_pred = nn.Linear(320, self.dict_size)
        self.color_pred1 = nn.Linear(320, 6)

    def forward(self, img: Tensor) -> Tuple[Tensor, Tensor]:
        feats = self.backbone(img).squeeze(2)          # [B, 320, W/8]
        feats = self.encoders(feats.permute(0, 2, 1))   # [B, W/8, 320]
        pred_char_logits = self.char_pred(self.char_pred_norm(feats))  # [B, W/8, dict_size]
        pred_color_values = self.color_pred1(feats)                     # [B, W/8, 6]
        return pred_char_logits, pred_color_values


# ============================================================
# Export
# ============================================================

def export_to_onnx(checkpoint_path: Path, alphabet_path: Path, output_path: Path):
    print("Loading alphabet: {}".format(alphabet_path))
    with open(alphabet_path, 'r', encoding='utf-8') as fp:
        dictionary = [s[:-1] for s in fp.readlines()]
    print("  Dictionary size: {}".format(len(dictionary)))

    print("Loading checkpoint: {}".format(checkpoint_path))
    checkpoint = torch.load(checkpoint_path, map_location='cpu')
    state_dict = checkpoint['model'] if 'model' in checkpoint else checkpoint

    # Delete positional encoding buffers (will be re-created by model init)
    keys_to_delete = [k for k in state_dict.keys() if '.pe.pe' in k]
    for k in keys_to_delete:
        del state_dict[k]
        print("  Deleted buffer: {}".format(k))

    model = OCR(dictionary, 768)
    model.load_state_dict(state_dict, strict=False)
    model.eval()
    print("  Model loaded successfully")

    # Dummy input: batch=1, 3, 48, 256 (width=256 is arbitrary, will be dynamic)
    dummy_input = torch.randn(1, 3, 48, 256)

    # Verify forward pass
    with torch.no_grad():
        char_logits, color_values = model(dummy_input)
    print("  Forward pass OK: char_logits={}, color_values={}".format(char_logits.shape, color_values.shape))

    output_path.parent.mkdir(parents=True, exist_ok=True)

    print("Exporting to ONNX: {}".format(output_path))
    torch.onnx.export(
        model,
        dummy_input,
        str(output_path),
        export_params=True,
        opset_version=18,
        do_constant_folding=True,
        input_names=["images"],
        output_names=["char_logits", "color_values"],
        dynamic_axes={
            "images": {0: "batch_size", 3: "width"},
            "char_logits": {0: "batch_size", 1: "seq_len"},
            "color_values": {0: "batch_size", 1: "seq_len"},
        },
        dynamo=False,
    )

    file_size = output_path.stat().st_size
    print("  Export complete: {:.1f} MB".format(file_size / 1024 / 1024))

    # Verify the exported model
    import onnx
    onnx_model = onnx.load(str(output_path))
    onnx.checker.check_model(onnx_model)
    print("  ONNX model verification passed")

    # Print model info
    print("")
    print("  Inputs:")
    for inp in onnx_model.graph.input:
        dims = [d.dim_param or d.dim_value for d in inp.type.tensor_type.shape.dim]
        print("    {}: {}".format(inp.name, dims))
    print("  Outputs:")
    for out in onnx_model.graph.output:
        dims = [d.dim_param or d.dim_value for d in out.type.tensor_type.shape.dim]
        print("    {}: {}".format(out.name, dims))


def main():
    if not CHECKPOINT_PATH.exists():
        print("Error: checkpoint not found: {}".format(CHECKPOINT_PATH))
        print("Please download ocr-ctc.ckpt first")
        sys.exit(1)

    if not ALPHABET_PATH.exists():
        print("Error: alphabet not found: {}".format(ALPHABET_PATH))
        sys.exit(1)

    output_path = OUTPUT_DIR / "model.onnx"
    export_to_onnx(CHECKPOINT_PATH, ALPHABET_PATH, output_path)

    # Also copy alphabet to output dir
    import shutil
    alphabet_out = OUTPUT_DIR / "alphabet-all-v5.txt"
    shutil.copy2(ALPHABET_PATH, alphabet_out)
    print("")
    print("Alphabet copied: {}".format(alphabet_out))

    print("")
    print("Done! Output directory: {}".format(OUTPUT_DIR))
    print("Next steps:")
    print("  1. Copy {} to app/src/main/assets/ocr_ctc/model.onnx".format(output_path))
    print("  2. Copy {} to app/src/main/assets/ocr_ctc/alphabet-all-v5.txt".format(alphabet_out))


if __name__ == "__main__":
    main()
