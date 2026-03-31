import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from database import init_db, close_db
from services.queue_service import cleanup_expired


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    await init_db()

    # Background task: cleanup expired queue messages every 5 minutes
    async def periodic_cleanup():
        while True:
            await asyncio.sleep(300)
            try:
                await cleanup_expired()
            except Exception:
                pass

    task = asyncio.create_task(periodic_cleanup())

    yield

    # Shutdown
    task.cancel()
    await close_db()


app = FastAPI(title="Stego API", version="0.2.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Import and register routers
from api.v1.stego import router as stego_router
from api.v1.auth import router as auth_router
from api.v1.invite import router as invite_router
from api.v1.ws import router as ws_router

app.include_router(stego_router)
app.include_router(auth_router)
app.include_router(invite_router)
app.include_router(ws_router)


@app.get("/health")
async def health():
    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
