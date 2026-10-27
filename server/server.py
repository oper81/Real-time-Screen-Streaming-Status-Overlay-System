"""
Асинхронный WebSocket-сервер на FastAPI со встроенным стримингом экрана.

Роли подключений задаются query-параметром ?role=:
  - ws://YOUR-DOMAIN:8000/ws?role=device      — подключается Android-клиент (отправитель кадров / получатель оверлея)
  - wss://YOUR-DOMAIN  — подключается панель управления (получатель кадров / отправитель оверлея)

Запуск:
    pip install fastapi uvicorn websockets pydantic
    uvicorn server:app --host 0.0.0.0 --port 8000
"""

import json
import logging
from typing import Optional
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse  # ИСПРАВЛЕНО: Правильный импорт
from pydantic import BaseModel, ValidationError

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("status-overlay-server")

app = FastAPI(title="Status Overlay Demo Server")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

class StatusPayload(BaseModel):
    group: int
    status: int

class ConnectionManager:
    """Управляет активными WebSocket сессиями смартфона и панели напарника."""
    def __init__(self) -> None:
        self.device: Optional[WebSocket] = None
        self.controller: Optional[WebSocket] = None

    async def connect(self, role: str, websocket: WebSocket) -> None:
        await websocket.accept()
        if role == "device":
            self.device = websocket
            logger.info("Device (Смартфон) подключен")
        elif role == "controller":
            self.controller = websocket
            logger.info("Controller (Панель напарника) подключен")

    def disconnect(self, role: str) -> None:
        if role == "device":
            self.device = None
            logger.info("Device (Смартфон) отключен")
        elif role == "controller":
            self.controller = None
            logger.info("Controller (Панель напарника) отключен")

    async def send_text_to_device(self, payload: dict) -> bool:
        if self.device is None:
            logger.warning("Нет подключенного device — подсказка не доставлена")
            return False
        await self.device.send_text(json.dumps(payload))
        return True

    async def send_bytes_to_controller(self, data: bytes) -> None:
        if self.controller is not None:
            await self.controller.send_bytes(data)

manager = ConnectionManager()

# --- Встроенная HTML/JS Панель Мониторинга и Управления ---
HTML_INTERFACE = """
<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Панель управления — Status Overlay Demo</title>
<style>
  :root {
    --bg: #f4f5f7;
    --card: #ffffff;
    --text: #1b1f23;
    --muted: #6b7280;
    --accent: #2563eb;
    --ok: #16a34a;
    --err: #dc2626;
    color-scheme: light dark;
  }
  @media (prefers-color-scheme: dark) {
    :root:not([data-theme="light"]) {
      --bg: #0f1115;
      --card: #1a1d23;
      --text: #e8eaed;
      --muted: #9aa0a6;
      --accent: #5b8def;
    }
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    padding: 24px;
    gap: 30px;
  }
  .card {
    background: var(--card);
    border-radius: 16px;
    padding: 28px;
    width: 100%;
    max-width: 380px;
    box-shadow: 0 10px 30px rgba(0,0,0,0.12);
  }
  .stream-card {
    max-width: 400px;
    text-align: center;
  }
  #screenView {
    max-width: 100%;
    border: 3px solid rgba(128,128,128,0.3);
    border-radius: 10px;
    background: #000;
    min-height: 500px;
  }
  h1 { font-size: 18px; margin: 0 0 4px; }
  p.sub { color: var(--muted); font-size: 13px; margin: 0 0 20px; }
  label { display: block; font-size: 13px; color: var(--muted); margin-bottom: 6px; }
  input[type="number"], input[type="text"] {
    width: 100%;
    padding: 10px 12px;
    border-radius: 10px;
    border: 1px solid rgba(128,128,128,0.3);
    background: transparent;
    color: var(--text);
    font-size: 15px;
    margin-bottom: 16px;
  }
  button {
    width: 100%;
    padding: 12px;
    border: none;
    border-radius: 10px;
    background: var(--accent);
    color: white;
    font-size: 15px;
    font-weight: 600;
    cursor: pointer;
  }
  button:disabled { opacity: 0.5; cursor: not-allowed; }
  .status-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 16px;
    font-size: 13px;
    color: var(--muted);
  }
  .dot {
    width: 8px; height: 8px; border-radius: 50%;
    background: var(--err);
  }
  .dot.connected { background: var(--ok); }
  #log {
    margin-top: 16px;
    font-size: 12px;
    color: var(--muted);
    max-height: 100px;
    overflow-y: auto;
  }
</style>
</head>
<body>

  <!-- Левая панель: Стрим экрана смартфона -->
  <div class="card stream-card">
    <h1>Трансляция экрана</h1>
    <p class="sub">Мониторинг экрана в реальном времени</p>
    <img id="screenView" src="" alt="Ожидание подключения смартфона..." />
  </div>

  <!-- Правая панель: Отправка кодов -->
  <div class="card">
    <h1>Status Overlay — Controller</h1>
    <p class="sub">Отправка тестовых кодов на Android-устройство</p>

    <div class="status-row">
      <span class="dot" id="connDot"></span>
      <span id="connText">Подключение к серверу…</span>
    </div>

    <label for="serverUrl">Адрес сервера (WebSocket)</label>
    <input type="text" id="serverUrl" value="">

    <label for="groupInput">Group ID (Две цифры вопроса)</label>
    <input type="number" id="groupInput" value="12" min="0" max="99">

    <label for="statusInput">Status Code (Номер ответа)</label>
    <input type="number" id="statusInput" value="3" min="0" max="9">

    <button id="sendBtn" disabled>Отправить</button>

    <div id="log"></div>
  </div>

<script>
let ws = null;

const connDot = document.getElementById('connDot');
const connText = document.getElementById('connText');
const sendBtn = document.getElementById('sendBtn');
const serverUrlInput = document.getElementById('serverUrl');
const logEl = document.getElementById('log');
const imgView = document.getElementById('screenView');

// Автоматически подставляем текущий хост страницы
serverUrlInput.value = `wss://${window.location.host}/ws?role=controller`;

function log(msg) {
  const line = document.createElement('div');
  line.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
  logEl.prepend(line);
}

function connect() {
  const url = serverUrlInput.value.trim();
  if (!url) return;

  ws = new WebSocket(url);

  ws.onopen = () => {
    connDot.classList.add('connected');
    connText.textContent = 'Подключено к серверу';
    sendBtn.disabled = false;
    log('Соединение установлено');
  };

  ws.onclose = () => {
    connDot.classList.remove('connected');
    connText.textContent = 'Нет соединения — переподключение через 3с';
    sendBtn.disabled = true;
    setTimeout(connect, 3000);
  };

  ws.onerror = () => {
    log('Ошибка соединения');
  };

  ws.onmessage = async (event) => {
    // Если сервер прислал бинарные данные (видеокадр JPEG от устройства)
    if (event.data instanceof Blob) {
      const url = URL.createObjectURL(event.data);
      const oldUrl = imgView.src;
      imgView.src = url;
      if (oldUrl.startsWith("blob:")) {
        URL.revokeObjectURL(oldUrl); // Очистка памяти
      }
    } else {
      log(`Ответ сервера: ${event.data}`);
    }
  };
}

document.getElementById('sendBtn').addEventListener('click', () => {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;

  const group = parseInt(document.getElementById('groupInput').value, 10);
  const status = parseInt(document.getElementById('statusInput').value, 10);

  if (Number.isNaN(group) || Number.isNaN(status)) {
    log('Введите корректные числовые значения');
    return;
  }

  const payload = { group: group, status: status };
  ws.send(JSON.stringify(payload));
  log(`Отправлено: Group=${group}, Status=${status}`);
  
  // Автоматический инкремент вопроса для удобства напарника
  let nextGroup = group + 1;
  if (nextGroup > 99) nextGroup = 99;
  document.getElementById('groupInput').value = nextGroup;
  document.getElementById('statusInput').value = "";
  document.getElementById('statusInput').focus();
});

// Отправка по нажатию кнопки Enter
window.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !sendBtn.disabled) {
        document.getElementById('sendBtn').click();
    }
});

connect();
</script>
</body>
</html>
"""

@app.get("/", response_class=HTMLResponse)  # ИСПРАВЛЕНО: Изменен класс ответа
async def get_panel():
    return HTML_INTERFACE

@app.get("/health")
async def health_check():
    return {
        "status": "ok",
        "device_connected": manager.device is not None,
        "controller_connected": manager.controller is not None,
    }

@app.websocket("/ws")
async def websocket_endpoint(
    websocket: WebSocket,
    role: str = Query(..., regex="^(device|controller)$"),
):
    await manager.connect(role, websocket)
    try:
        while True:
            # Универсальный обработчик входящих сообщений
            message = await websocket.receive()
            
            # 1. Если пришли бинарные данные (кадр экрана от смартфона)
            if "bytes" in message and role == "device":
                frame_data = message["bytes"]
                # Пересылаем кадр напрямую напарнику (контроллеру)
                await manager.send_bytes_to_controller(frame_data)
                
                        # 2. Если пришли текстовые данные (команда от напарника)
            elif "text" in message and role == "controller":
                raw_text = message["text"]
                try:
                    data = json.loads(raw_text)
                    # Проверяем структуру данных через Pydantic-модель
                    payload = StatusPayload(**data)
                    # Пересылаем валидный JSON на смартфон
                    delivered = await manager.send_text_to_device(payload.model_dump())
                    await websocket.send_text(json.dumps({"delivered": delivered}))
                except (json.JSONDecodeError, ValidationError) as exc:
                    logger.warning("Некорректный JSON от controller: %s (%s)", raw_text, exc)
                    await websocket.send_text(json.dumps({"error": "invalid payload"}))
                    continue

    except WebSocketDisconnect:
        manager.disconnect(role)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)