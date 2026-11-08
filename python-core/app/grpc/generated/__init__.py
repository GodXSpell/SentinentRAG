"""
Generated gRPC and Protobuf stubs for Sentinel-RAG.
"""

import sys
from pathlib import Path

_DIR = str(Path(__file__).resolve().parent)
if _DIR not in sys.path:
    sys.path.insert(0, _DIR)

import rag_service_pb2
import rag_service_pb2_grpc

__all__ = ["rag_service_pb2", "rag_service_pb2_grpc"]

