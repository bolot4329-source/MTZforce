#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
rustore_publish.py — публикация новой версии приложения в RuStore через Public API.

Реализация по ревью Gemini (2026-11): актуальная схема авторизации
(POST /public/auth, keyId + timestamp, заголовок Public-Token),
v2-эндпоинты для чтения/редактирования черновика, priorityUpdate 0..5,
TLS через REQUESTS_CA_BUNDLE (сертификаты НУЦ Минцифры).

Секреты читаются ТОЛЬКО из переменных окружения (никогда не передавать
их аргументами командной строки — они попадут в логи CI):
  RUSTORE_KEY_ID        — Key ID из RuStore Console (раздел «API RuStore»)
  RUSTORE_PRIVATE_KEY   — приватный ключ (Base64 PKCS#8 DER, как скачан из консоли;
                          допустимы PEM-заголовки и переносы строк — будут очищены)
  RUSTORE_PACKAGE_NAME  — packageName приложения (например, ru.example.app)

Необязательные переменные окружения:
  RUSTORE_API_BASE_URL  — базовый URL API
                          (по умолчанию https://public-api-m.rustore.ru;
                          фолбэк: https://public-api.rustore.ru)
  REQUESTS_CA_BUNDLE    — путь к PEM-бандлу с сертификатами НУЦ Минцифры
                          (стандартная переменная requests; на GitHub Actions
                          её выставляет шаг подготовки CA в workflow)

Зависимости: requests, cryptography
  pip install requests cryptography

Примеры:
  python3 scripts/rustore_publish.py --file app-release.apk --whats-new "Исправления"
  python3 scripts/rustore_publish.py --file app-release.aab --publish-type MANUAL --no-submit
  python3 scripts/rustore_publish.py --file app.apk --priority-update 3 --reuse-draft
"""

from __future__ import annotations

import argparse
import base64
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

DEFAULT_BASE_URL = "https://public-api-m.rustore.ru"
FALLBACK_BASE_URL = "https://public-api.rustore.ru"

# Таймауты запросов: (connect, read). 300 сек на передачу — для файлов 100+ МБ.
TIMEOUT = (15, 300)
# Ретраи при сетевых ошибках и кодах 502/503/504.
MAX_RETRIES = 3
RETRYABLE_STATUS = {502, 503, 504}

VALID_PUBLISH_TYPES = {"MANUAL", "INSTANTLY", "DELAYED"}


class RuStoreError(RuntimeError):
    """Ошибка вызова RuStore API с деталями ответа сервера."""


def log(msg: str) -> None:
    print(f"[rustore] {msg}", flush=True)


def fail(msg: str) -> "SystemExit":
    print(f"[rustore][ERROR] {msg}", file=sys.stderr, flush=True)
    return SystemExit(1)


def required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise fail(f"Не задана обязательная переменная окружения {name}")
    return value


def load_private_key(raw: str):
    """Загрузить приватный ключ RuStore.

    Ключ из консоли приходит как чистая Base64-строка (PKCS#8 DER), но при
    копировании в GitHub Secrets могут появиться PEM-заголовки, пробелы и
    переносы строк — всё это очищаем.
    """
    cleaned = (
        raw.replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("-----BEGIN RSA PRIVATE KEY-----", "")
        .replace("-----END RSA PRIVATE KEY-----", "")
    )
    cleaned = "".join(cleaned.split())  # убрать все пробельные символы
    try:
        der = base64.b64decode(cleaned)
    except Exception as exc:  # noqa: BLE001
        raise fail(f"RUSTORE_PRIVATE_KEY не декодируется из Base64: {exc}")
    try:
        return serialization.load_der_private_key(der, password=None)
    except Exception as exc:  # noqa: BLE001
        raise fail(f"Не удалось загрузить приватный ключ (ожидается PKCS#8 DER): {exc}")


def sign_auth(key, key_id: str, timestamp: str) -> str:
    """SHA512withRSA (PKCS#1 v1.5) подпись строки keyId + timestamp, в Base64."""
    data = f"{key_id}{timestamp}".encode("utf-8")
    signature = key.sign(data, padding.PKCS1v15(), hashes.SHA512())
    return base64.b64encode(signature).decode("ascii")


def iso_timestamp() -> str:
    """ISO-8601 с миллисекундами и часовым поясом, как требует RuStore API."""
    now = datetime.now(timezone.utc)
    return now.strftime("%Y-%m-%dT%H:%M:%S.") + f"{now.microsecond // 1000:03d}+00:00"


def describe_response(resp: requests.Response) -> str:
    try:
        return resp.text[:2000]
    except Exception:  # noqa: BLE001
        return f"<HTTP {resp.status_code}, тело недоступно>"


def api_request(
    session: requests.Session,
    method: str,
    url: str,
    *,
    token: str | None = None,
    retries: int = MAX_RETRIES,
    **kwargs,
) -> requests.Response:
    """HTTP-запрос к API с ретраями при сетевых сбоях и 502/503/504."""
    headers = kwargs.pop("headers", {}) or {}
    if token:
        headers["Public-Token"] = token
    kwargs.setdefault("timeout", TIMEOUT)

    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            resp = session.request(method, url, headers=headers, **kwargs)
        except (requests.ConnectionError, requests.Timeout) as exc:
            last_error = exc
            wait = 2**attempt
            log(f"Сетевая ошибка ({exc.__class__.__name__}), "
                f"попытка {attempt}/{retries}, пауза {wait} с...")
            time.sleep(wait)
            continue
        if resp.status_code in RETRYABLE_STATUS and attempt < retries:
            wait = 2**attempt
            log(f"HTTP {resp.status_code} от сервера, попытка {attempt}/{retries}, "
                f"пауза {wait} с...")
            time.sleep(wait)
            continue
        return resp
    raise fail(f"Запрос {method} {url} не удался после {retries} попыток: {last_error}")


def check_code(resp: requests.Response, action: str) -> dict:
    """Проверить ответ API и вернуть распарсенный JSON."""
    if resp.status_code >= 400:
        raise fail(f"{action}: HTTP {resp.status_code}. Ответ API: {describe_response(resp)}")
    try:
        payload = resp.json()
    except ValueError:
        raise fail(f"{action}: не JSON в ответе. Ответ API: {describe_response(resp)}")
    code = payload.get("code")
    if code != "OK":
        raise fail(f"{action}: code={code}. Ответ API: {describe_response(resp)}")
    return payload


def get_token(session: requests.Session, base_url: str, key_id: str, key) -> str:
    timestamp = iso_timestamp()
    body = {
        "keyId": key_id,
        "timestamp": timestamp,
        "signature": sign_auth(key, key_id, timestamp),
    }
    resp = api_request(
        session, "POST", f"{base_url}/public/auth",
        json=body, headers={"Content-Type": "application/json"},
    )
    payload = check_code(resp, "Получение токена")
    token = (payload.get("body") or {}).get("jwe")
    if not token:
        raise fail(f"Получение токена: нет body.jwe. Ответ API: {describe_response(resp)}")
    # Маскируем токен в логах GitHub Actions (на случай утечки заголовков при ошибках).
    print(f"::add-mask::{token}", flush=True)
    log("Токен авторизации получен.")
    return token


def find_draft(session, base_url, package_name, token):
    """Найти существующий черновик версии (versionStatus == DRAFT)."""
    resp = api_request(
        session, "GET",
        f"{base_url}/public/v1/application/{package_name}/version",
        token=token, params={"page": 0, "size": 20},
    )
    payload = check_code(resp, "Получение списка версий")
    content = (payload.get("body") or {}).get("content") or []
    for item in content:
        if item.get("versionStatus") == "DRAFT":
            return item.get("versionId")
    return None


def delete_version(session, base_url, package_name, version_id, token):
    resp = api_request(
        session, "DELETE",
        f"{base_url}/public/v1/application/{package_name}/version/{version_id}",
        token=token,
    )
    check_code(resp, f"Удаление черновика {version_id}")
    log(f"Старый черновик {version_id} удалён.")


def create_draft(session, base_url, package_name, token, whats_new, publish_type,
                 publish_datetime=None, partial_value=None):
    body: dict = {"publishType": publish_type}
    if whats_new:
        body["whatsNew"] = whats_new[:5000]
    if publish_type == "DELAYED" and publish_datetime:
        body["publishDateTime"] = publish_datetime
    if partial_value is not None:
        body["partialValue"] = partial_value
    try:
        resp = api_request(
            session, "POST",
            f"{base_url}/public/v1/application/{package_name}/version",
            token=token, json=body, headers={"Content-Type": "application/json"},
        )
        payload = check_code(resp, "Создание черновика версии")
    except SystemExit:
        # Идемпотентность при 5xx: шлюз мог вернуть 502/503/504, но черновик
        # на сервере фактически создаться. Перед повторной попыткой/падением
        # проверяем список версий: если DRAFT появился — используем его.
        log("Ошибка при создании черновика. Проверяю, не создался ли он на сервере...")
        recovered_id = find_draft(session, base_url, package_name, token)
        if recovered_id is not None:
            log(f"Черновик всё-таки существует, versionId={recovered_id} — использую его.")
            return recovered_id
        raise
    version_id = payload.get("body")
    if not isinstance(version_id, int):
        raise fail(f"Создание черновика: неожиданный body. Ответ API: {describe_response(resp)}")
    log(f"Черновик версии создан, versionId={version_id}.")
    return version_id


def upload_file(session, base_url, package_name, version_id, token, file_path: Path):
    suffix = file_path.suffix.lower()
    if suffix == ".aab":
        # Для AAB query-параметры isMainApk/servicesType НЕ используются.
        url = f"{base_url}/public/v1/application/{package_name}/version/{version_id}/aab"
        params = None
    else:
        url = f"{base_url}/public/v1/application/{package_name}/version/{version_id}/apk"
        params = {"isMainApk": "true", "servicesType": "Unknown"}
    size_mb = file_path.stat().st_size / (1024 * 1024)
    log(f"Загрузка {file_path.name} ({size_mb:.1f} МБ) как {suffix.lstrip('.').upper()}...")
    with open(file_path, "rb") as fh:
        resp = api_request(
            session, "POST", url, token=token, params=params,
            files={"file": (file_path.name, fh, "application/octet-stream")},
        )
    payload = check_code(resp, "Загрузка файла сборки")
    log("Файл сборки принят сервером.")
    return payload


def verify_draft(session, base_url, package_name, version_id, token):
    """Контрольная проверка: файл действительно прикреплён к черновику."""
    resp = api_request(
        session, "GET",
        f"{base_url}/public/v2/application/{package_name}/version/{version_id}",
        token=token,
    )
    payload = check_code(resp, "Проверка черновика")
    body = payload.get("body") or {}
    files = body.get("fileUids") or []
    packages = body.get("packages") or []
    if not files and not packages:
        raise fail("Проверка черновика: файл не прикреплён (fileUids и packages пусты). "
                   f"Ответ API: {describe_response(resp)}")
    version_codes = [p.get("versionCode") for p in packages if isinstance(p, dict)]
    log(f"Проверка пройдена: файлов прикреплено: {len(files)}, "
        f"versionCode: {version_codes or 'н/д'}.")


def commit_version(session, base_url, package_name, version_id, token, priority_update):
    resp = api_request(
        session, "POST",
        f"{base_url}/public/v1/application/{package_name}/version/{version_id}/commit",
        token=token, params={"priorityUpdate": priority_update},
    )
    check_code(resp, "Отправка на модерацию")
    log(f"Версия отправлена на модерацию (priorityUpdate={priority_update}).")


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="Публикация новой версии приложения в RuStore через Public API.",
    )
    parser.add_argument("--file", required=True, type=Path,
                        help="Путь к подписанному APK или AAB файлу.")
    parser.add_argument("--whats-new", default=None,
                        help='Текст «Что нового» (до 5000 символов).')
    parser.add_argument("--whats-new-file", type=Path, default=None,
                        help="Путь к файлу с текстом «Что нового» (приоритетнее --whats-new).")
    parser.add_argument("--publish-type", default="INSTANTLY",
                        choices=sorted(VALID_PUBLISH_TYPES),
                        help="Тип публикации после модерации (по умолчанию INSTANTLY).")
    parser.add_argument("--priority-update", type=int, default=0, choices=range(0, 6),
                        metavar="0-5",
                        help="Приоритет обновления 0..5 (по умолчанию 0).")
    parser.add_argument("--submit", dest="submit", action="store_true", default=True,
                        help="Отправить версию на модерацию (по умолчанию включено).")
    parser.add_argument("--no-submit", dest="submit", action="store_false",
                        help="Только создать черновик и загрузить файл, без отправки "
                             "на модерацию (режим тестового прогона).")
    parser.add_argument("--reuse-draft", action="store_true",
                        help="Не удалять существующий черновик, а использовать его "
                             "(если черновик подготовлен вручную в веб-консоли).")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)

    file_path: Path = args.file
    if not file_path.is_file():
        raise fail(f"Файл сборки не найден: {file_path}")
    if file_path.suffix.lower() not in {".apk", ".aab"}:
        raise fail(f"Ожидается файл .apk или .aab, получен: {file_path.name}")

    key_id = required_env("RUSTORE_KEY_ID")
    package_name = required_env("RUSTORE_PACKAGE_NAME")
    private_key = load_private_key(required_env("RUSTORE_PRIVATE_KEY"))

    whats_new = None
    if args.whats_new_file:
        if not args.whats_new_file.is_file():
            raise fail(f"Файл «Что нового» не найден: {args.whats_new_file}")
        whats_new = args.whats_new_file.read_text(encoding="utf-8").strip()
    elif args.whats_new:
        whats_new = args.whats_new.strip()

    base_url = os.environ.get("RUSTORE_API_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
    log(f"RuStore API: {base_url}; приложение: {package_name}")

    session = requests.Session()

    # Шаг 1. Авторизация. При ошибке TLS на новом домене — фолбэк на старый.
    try:
        token = get_token(session, base_url, key_id, private_key)
    except requests.exceptions.SSLError:
        if base_url == DEFAULT_BASE_URL:
            log("Ошибка TLS на основном домене. Проверьте REQUESTS_CA_BUNDLE "
                "(сертификаты НУЦ Минцифры). Пробую фолбэк-домен...")
            base_url = FALLBACK_BASE_URL
            token = get_token(session, base_url, key_id, private_key)
        else:
            raise

    # Шаг 2. Идемпотентность: найти и (по умолчанию) удалить старый черновик.
    draft_id = find_draft(session, base_url, package_name, token)
    version_id = None
    if draft_id is not None:
        if args.reuse_draft:
            version_id = draft_id
            log(f"Использую существующий черновик versionId={version_id} (--reuse-draft).")
        else:
            delete_version(session, base_url, package_name, draft_id, token)

    # Шаг 3. Создать черновик (если не переиспользуем существующий).
    if version_id is None:
        version_id = create_draft(
            session, base_url, package_name, token,
            whats_new=whats_new, publish_type=args.publish_type,
        )

    # Шаг 4. Загрузить APK/AAB.
    upload_file(session, base_url, package_name, version_id, token, file_path)

    # Шаг 5. Контрольная проверка прикрепления файла.
    verify_draft(session, base_url, package_name, version_id, token)

    # Шаг 6. Отправка на модерацию.
    if args.submit:
        commit_version(session, base_url, package_name, version_id, token,
                       args.priority_update)
        log("Готово: версия на модерации. Следите за статусом в RuStore Console.")
    else:
        log("Готово (--no-submit): черновик создан, файл загружен и проверен. "
            "Отправку на модерацию можно выполнить вручную в RuStore Console.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
