"""
server.py (app/grpc/server.py)
------------------------------
Compatibility alias for Sentinel-RAG gRPC Server.
Delegates directly to the production async gRPC server in grpc_server.server,
which implements GetEngineInfo, QueryRAG, PrepareIndex, and RunEvaluation with
full hybrid search, reranking, and NLI evaluation.
"""

import asyncio
from grpc_server.server import serve

if __name__ == "__main__":
    asyncio.run(serve())