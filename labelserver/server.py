"""Сервер печати этикеток МойСклад: принимает код и количество, печатает PDF через SumatraPDF."""
import configparser
import logging
import os
import re
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

import requests
from flask import Flask, jsonify, request

API = "https://api.moysklad.ru/api/remap/1.2"
BASE_DIR = Path(__file__).resolve().parent
CODE_RE = re.compile(r"^[\w.\-]{1,64}$")
LABEL_TYPES = ("product", "variant", "bundle")
REDIRECTS = (301, 302, 303, 307, 308)
PDF_MAX_AGE_SECONDS = 24 * 3600
PRINT_TIMEOUT_SECONDS = 120

log = logging.getLogger("labelserver")


@dataclass
class Config:
    login: str
    password: str
    printer: str
    sumatra_path: str
    template: str = "Термоэтикетка (58х40мм)"
    organization: str = ""
    price_type: str = ""
    host: str = "0.0.0.0"
    port: int = 5000
    print_settings: str = "fit"
    max_count: int = 500
    pdf_dir: Path = BASE_DIR / "pdf"


class LabelError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def load_config(path=None):
    path = Path(path or os.environ.get("LABEL_CONFIG") or BASE_DIR / "config.ini")
    parser = configparser.ConfigParser(interpolation=None)
    if not parser.read(path, encoding="utf-8") or "server" not in parser:
        raise SystemExit(f"Не найден файл настроек {path} (раздел [server]). "
                         "Скопируйте config.example.ini в config.ini и заполните.")
    values = {key: value.strip() for key, value in parser["server"].items()}
    missing = [key for key in ("login", "password", "printer", "sumatra_path") if not values.get(key)]
    if missing:
        raise SystemExit(f"В {path} не заполнены параметры: {', '.join(missing)}")
    optional = {key: values[key] for key in ("template", "organization", "price_type", "host", "print_settings")
                if key in values}
    return Config(
        login=values["login"],
        password=values["password"],
        printer=values["printer"],
        sumatra_path=values["sumatra_path"],
        port=int(values.get("port", 5000)),
        max_count=int(values.get("max_count", 500)),
        **optional,
    )


def normalize(text):
    return text.lower().replace("x", "х").replace(" ", "")


def error_text(response):
    try:
        return response.json()["errors"][0]["error"]
    except (ValueError, KeyError, IndexError, TypeError):
        return f"HTTP {response.status_code}"


class MoySklad:
    def __init__(self, cfg):
        self.cfg = cfg
        self.http = requests.Session()
        self.http.auth = (cfg.login, cfg.password)

    def _check(self, response):
        if response.status_code in (401, 403):
            raise LabelError("МойСклад отклонил логин или пароль", 502)
        if response.status_code >= 400:
            raise LabelError(f"МойСклад: {error_text(response)}", 502)

    def _request(self, method, url, **kwargs):
        try:
            return self.http.request(method, url, timeout=kwargs.pop("timeout", 30), **kwargs)
        except requests.RequestException as e:
            raise LabelError("Нет связи с МойСклад с компьютера печати", 502) from e

    def get(self, path, **params):
        response = self._request("GET", API + path, params=params)
        self._check(response)
        return response.json()

    def find_product(self, code):
        # Точные фильтры, как в приложении: search даёт частичные совпадения.
        for field in ("barcode", "code", "article"):
            rows = self.get("/entity/assortment", filter=f"{field}={code}", limit=1)["rows"]
            if rows:
                return rows[0]
        raise LabelError(f"Товар с кодом или штрихкодом «{code}» не найден", 404)

    def pick(self, rows, wanted, what):
        if not rows:
            raise LabelError(f"В МойСклад нет данных: {what}", 500)
        if not wanted:
            return rows[0]
        for row in rows:
            if normalize(row["name"]) == normalize(wanted):
                return row
        available = ", ".join(row["name"] for row in rows)
        raise LabelError(f"Не найдено: {what} «{wanted}». Доступно: {available}", 500)

    def export_pdf(self, product, count):
        kind = product["meta"]["type"]
        if kind not in LABEL_TYPES:
            raise LabelError("Для этой позиции этикетку напечатать нельзя", 400)
        organization = self.pick(self.get("/entity/organization")["rows"], self.cfg.organization, "организация")
        price_type = self.pick(self.get("/context/companysettings/pricetype"), self.cfg.price_type, "тип цены")
        templates = (self.get("/entity/assortment/metadata/embeddedtemplate")["rows"]
                     + self.get("/entity/assortment/metadata/customtemplate")["rows"])
        template = self.pick(templates, self.cfg.template, "шаблон этикетки")

        body = {
            "organization": {"meta": organization["meta"]},
            "count": count,
            "salePrice": {"priceType": {"meta": price_type["meta"]}},
            "template": {"meta": template["meta"]},
        }
        response = self._request("POST", f"{API}/entity/{kind}/{product['id']}/export",
                                 json=body, allow_redirects=False, timeout=60)
        if response.status_code not in REDIRECTS:
            self._check(response)
            raise LabelError(f"МойСклад не вернул ссылку на PDF (HTTP {response.status_code})", 502)
        location = response.headers.get("Location")
        if not location:
            raise LabelError("МойСклад не вернул ссылку на PDF", 502)
        try:
            pdf = requests.get(location, timeout=60)
        except requests.RequestException as e:
            raise LabelError("Не удалось скачать PDF этикеток", 502) from e
        if pdf.status_code != 200 or not pdf.content.startswith(b"%PDF"):
            raise LabelError("Не удалось скачать PDF этикеток", 502)
        return pdf.content


def remove_old_pdfs(directory):
    limit = time.time() - PDF_MAX_AGE_SECONDS
    for file in directory.glob("*.pdf"):
        if file.stat().st_mtime < limit:
            file.unlink(missing_ok=True)


def print_pdf(cfg, data):
    cfg.pdf_dir.mkdir(exist_ok=True)
    remove_old_pdfs(cfg.pdf_dir)
    path = cfg.pdf_dir / f"{time.strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:6]}.pdf"
    path.write_bytes(data)
    command = [cfg.sumatra_path, "-print-to", cfg.printer, "-print-settings", cfg.print_settings,
               "-silent", str(path)]
    try:
        result = subprocess.run(command, capture_output=True, timeout=PRINT_TIMEOUT_SECONDS)
    except FileNotFoundError as e:
        raise LabelError(f"Не найден SumatraPDF: {cfg.sumatra_path}", 500) from e
    except subprocess.TimeoutExpired as e:
        raise LabelError(f"SumatraPDF не завершился за {PRINT_TIMEOUT_SECONDS} с", 500) from e
    if result.returncode != 0:
        details = result.stderr.decode("utf-8", errors="replace").strip()
        log.error("SumatraPDF код %s: %s", result.returncode, details)
        raise LabelError(f"SumatraPDF завершился с ошибкой (код {result.returncode}). Проверьте имя принтера", 500)


def create_app(cfg):
    app = Flask(__name__)
    app.json.ensure_ascii = False
    ms = MoySklad(cfg)
    lock = threading.Lock()

    @app.errorhandler(LabelError)
    def on_label_error(e):
        log.warning("Отказ %s: %s", e.status, e)
        return jsonify(error=str(e)), e.status

    @app.errorhandler(Exception)
    def on_unexpected(e):
        log.exception("Необработанная ошибка")
        return jsonify(error="Внутренняя ошибка сервера печати"), 500

    @app.get("/health")
    def health():
        return jsonify(ok=True, printer=cfg.printer)

    @app.post("/print")
    def print_labels():
        data = request.get_json(silent=True)
        if not isinstance(data, dict):
            raise LabelError("Ожидается JSON вида {\"code\": \"...\", \"count\": 1}")
        code = str(data.get("code", "")).strip()
        count = data.get("count")
        if not CODE_RE.match(code):
            raise LabelError("Некорректный код товара")
        if isinstance(count, bool) or not isinstance(count, int) or not 1 <= count <= cfg.max_count:
            raise LabelError(f"Количество должно быть целым числом от 1 до {cfg.max_count}")
        with lock:
            product = ms.find_product(code)
            print_pdf(cfg, ms.export_pdf(product, count))
        log.info("Напечатано: %s (код %s) x %s", product["name"], code, count)
        return jsonify(ok=True, name=product["name"], code=product.get("code", ""), printed=count)

    return app


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    cfg = load_config()
    from waitress import serve
    log.info("Сервер печати этикеток: http://%s:%s, принтер «%s»", cfg.host, cfg.port, cfg.printer)
    serve(create_app(cfg), host=cfg.host, port=cfg.port, threads=4)


if __name__ == "__main__":
    main()
