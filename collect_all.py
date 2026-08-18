import os
import re
import sys
import json
import base64
import random
import time
import logging
import traceback
import urllib.parse
import uvicorn
from playwright.sync_api import sync_playwright
from fastapi import FastAPI, Depends, HTTPException, status, Header, HTTPException
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

logger = logging.getLogger("uvicorn.error")

# ============ Конфигурация ============

BASE_URL = "https://zyoo.kinvd.xyz"
PASSWORD = "323211"
API_TOKEN = "cfifcfif"

# ============ Функции из parser2.js ============

def parse_file_field(file_str):
    """Разбор поля file: качество -> перевод -> список URL (порт parseFileField)."""
    result = {}
    if not file_str:
        return result

    quality_parts = re.split(r',(?=\[\d+p\])', file_str)
    for part in quality_parts:
        q_match = re.match(r'^\[(\d+p)\](.*)$', part, re.S)
        if not q_match:
            continue

        quality = q_match.group(1)
        rest = q_match.group(2)

        translations = {}
        trans_parts = re.split(r';(?=\{[^}]+\})', rest)
        for tp in trans_parts:
            t_match = re.match(r'^\{([^}]+)\}(.*)$', tp, re.S)
            if t_match:
                trans_name = t_match.group(1)
                urls = [u.strip() for u in re.split(r'\s+or\s+', t_match.group(2)) if u.strip()]
                translations[trans_name] = urls
            else:
                urls = [u.strip() for u in re.split(r'\s+or\s+', tp) if u.strip()]
                if urls:
                    translations['default'] = urls
        result[quality] = translations
    return result


def decode_at(vod_body, idx):
    """Декодирует base64-строку, начиная с idx, и пытается распарсить JSON (порт decodeAt)."""
    end = idx
    for i in range(idx, len(vod_body)):
        if not re.match(r'[A-Za-z0-9+/=]', vod_body[i]):
            end = i
            break
    if end == idx:
        end = len(vod_body)
    b64 = vod_body[idx:end]
    try:
        decoded = base64.b64decode(b64).decode('utf-8')
    except Exception:
        return None
    if '"folder"' not in decoded and 'сезон' not in decoded:
        return None

    full_json = '[' + decoded
    depth = 0
    in_string = False
    escape = False
    json_end = -1
    for i, ch in enumerate(full_json):
        if in_string:
            if escape:
                escape = False
            elif ch == '\\':
                escape = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch in '[{':
            depth += 1
        elif ch in ']}':
            depth -= 1
            if depth == 0:
                json_end = i
                break
    if json_end == -1:
        return None
    try:
        return json.loads(full_json[:json_end + 1])
    except Exception:
        return None


def decode_seasons(vod_body):
    """Ищет и декодирует данные сезонов в теле ответа /vod/ (порт decodeSeasons)."""
    markers = ['eyJ0aXRsZSI6']
    for marker in markers:
        idx = vod_body.find(marker)
        if idx != -1:
            decoded = decode_at(vod_body, idx)
            if decoded:
                return decoded
    candidates = re.findall(r'[A-Za-z0-9+/=]{500,}', vod_body)
    for cand in candidates:
        idx = vod_body.find(cand)
        decoded = decode_at(vod_body, idx)
        if decoded:
            return decoded
    return None


# ============ Авторизация (из kinovod.py) ============

def wait_delay():
    delay = random.uniform(4.0, 6.0)
    print(f"Ожидание {delay:.2f} сек...")
    time.sleep(delay)


def authorize(page, url):
    """Авторизуется на сайте, если есть форма пароля (порт из kinovod.py)."""
    page.goto(url, wait_until='networkidle', timeout=45000)

    # Проверяем, есть ли на странице скрытое поле пароля, что означает наличие формы входа
    if page.locator("input[name='kv_auth_pwd']").count() > 0:
        # Используем JS для гарантированной вставки PIN и эмуляции ручного ввода
        page.evaluate(f"""
            const clipInput = document.getElementById('kv-pin-clip');
            if (clipInput) {{
                clipInput.value = '{PASSWORD}';
                clipInput.dispatchEvent(new Event('input', {{ bubbles: true }}));
            }}
        """)
        
        # Даем сайту 3 секунды, чтобы отправить форму и перезагрузить страницу
        page.wait_for_timeout(3000)
        page.wait_for_load_state("networkidle", timeout=10000)
        print("Текущий URL после ввода PIN:", page.url)


    # Проверяем, не вывелась ли ошибка на экран
    # (В HTML за это отвечает элемент с id="kv-pin-status", но проверка по тексту надежнее)
    if "Неверный PIN" in page.content():
        raise Exception("Ошибка авторизации: неверный PIN от сайта")



# ============ Метод FILM (из parser2.js) ============

def parse_film(page, quality):
    print(f'Обработка страницы как фильм. Установка качества {quality}...')

    # 1. Сначала выбираем нужное качество на текущей странице (сохраняется в куки/localstorage сайта)
    try:
        select_quality(page, quality)
    except Exception as e:
        print(f'Предупреждение при выборе качества: {e}')

    # Контейнер для пойманных ссылок
    captured_urls = []

    # 2. Вешаем слушатель запросов для перехвата .m3u8 ПОСЛЕ перезагрузки
    def on_request(request):
        url = request.url
        if '.m3u8' in url:
            captured_urls.append(url)

    page.on('request', on_request)

    # 3. Перезагружаем страницу, чтобы применилось выбранное качество и пошел новый потокс
    print('Перезагрузка страницы для обновления потока...')
    page.reload(wait_until="domcontentloaded")

    # 4. Ждем инициализации плеера и появления тега video
    video_selector = 'video'
    try:
        page.wait_for_selector(video_selector, timeout=15000)
        page.wait_for_timeout(4000)  # Даем время на старт воспроизведения и отправку запросов
    except Exception:
        print('Таймаут ожидания плеера, пробуем собрать то, что успели перехватить...')

    # 5. Выбираем лучшую ссылку из перехваченных запросов или берем из тега video
    video_src = None
    
    if captured_urls:
        # Фильтруем мастер-плейлисты (как в методе для сериалов)
        master = [u for u in captured_urls if 'master-v' in u or 'index-v' not in u]
        video_src = master[0] if master else captured_urls[0]
        print(f'Ссылка перехвачена из сетевых запросов: {video_src}')
    else:
        # Фолбэк: если сеть не перехватила, дергаем напрямую из атрибутов video
        try:
            video_src = page.evaluate('''() => {
                const v = document.querySelector('video');
                return v ? (v.currentSrc || v.src) : null;
            }''')
            print(f'Ссылка взята из video.currentSrc: {video_src}')
        except Exception:
            pass

    if not video_src or not video_src.startswith('http'):
        raise Exception('Не удалось найти сгенерированный поток .m3u8 для фильма после перезагрузки.')

    # Снимаем слушатель, чтобы не вешать контекст
    page.remove_listener('request', on_request)

    print('Ссылка на видеопоток успешно извлечена!')
    return [{
        "title": "Фильм",
        "season_number": 1,
        "episodes": [{
            "title": "Полный фильм",
            "episode_number": 1,
            "id": "movie",
            "m3u8": video_src,
            "qualities": {
                quality: {"default": [video_src]}
            },
            "subtitle": ""
        }]
    }]



# ============ Метод SERIAL с сезонами (из parser2.js) ============

def parse_serial_with_seasons(page, raw_bodies):
    season_tabs = page.locator('text=/\\d+\\s+сезон/i')
    count = season_tabs.count()

    if count > 1:
        print(f'Обнаружено вкладок сезонов на странице: {count}. Прокликиваем для подгрузки данных...')
        for i in range(count):
            try:
                season_tabs.nth(i).click(timeout=2000)
                page.wait_for_timeout(1500)
            except Exception:
                try:
                    season_tabs.nth(i).evaluate('node => node.click()')
                    page.wait_for_timeout(1500)
                except Exception:
                    pass
    else:
        page.evaluate('() => window.scrollBy(0, 400)')
        page.wait_for_timeout(2000)

    if len(raw_bodies) == 0:
        raise Exception('Не удалось перехватить ответы /vod/. Проверьте, загружается ли плеер.')

    seasons_map = {}
    for body in raw_bodies:
        decoded = decode_seasons(body)
        if not decoded:
            continue
        for s in decoded:
            if s and s.get('title') and s['title'] not in seasons_map:
                seasons_map[s['title']] = s

    all_seasons = list(seasons_map.values())
    if len(all_seasons) == 0:
        raise Exception('Не удалось декодировать данные ни одного сезона')

    def season_sort_key(a):
        t = str(a.get('title', '999'))
        return int(t) if t.isdigit() else 999

    all_seasons.sort(key=season_sort_key)

    result = []
    for si, season in enumerate(all_seasons):
        episodes = []
        for ei, ep in enumerate(season.get('folder') or []):
            episodes.append({
                "title": ep.get('title'),
                "episode_number": ei + 1,
                "id": ep.get('id'),
                "m3u8": None,
                "qualities": parse_file_field(ep.get('file') or ''),
                "subtitle": ep.get('subtitle') or ''
            })
        result.append({
            "title": season.get('title'),
            "season_number": si + 1,
            "episodes": episodes
        })
    return result


# ============ Метод SERIAL без сезонов (из collect_m3u8.js) ============

def parse_serial_no_seasons(page, quality):
    page.wait_for_selector('#videoplayer_playlist', timeout=20000)
    page.wait_for_timeout(5000)

    m3u8_by_series = {}
    active_fid = 0

    def on_request(request):
        url = request.url
        if '.m3u8' in url:
            if active_fid not in m3u8_by_series:
                m3u8_by_series[active_fid] = set()
            m3u8_by_series[active_fid].add(url)

    page.on('request', on_request)

    # Переключаем качество на желаемое
    select_quality(page, quality)

    counts = page.evaluate('''() => {
        const playlist = document.querySelector('#videoplayer_playlist');
        const items = playlist.querySelectorAll('[fid]');
        return { count: items.length, texts: Array.from(items).map(i => i.textContent.trim()) };
    }''')

    print(f'Серий: {counts["count"]}')

    # 1-я серия: читаем m3u8 из video.currentSrc
    first_src = page.evaluate('''() => {
        const v = document.querySelector('video');
        return v ? (v.currentSrc || v.src) : null;
    }''')
    print(f'[{counts["texts"][0]}] (currentSrc) -> {first_src}')
    if first_src and first_src.startswith('http'):
        m3u8_by_series[0] = {first_src}

    # Остальные серии: кликаем и перехватываем
    for fid in range(1, counts['count']):
        active_fid = fid
        if fid not in m3u8_by_series:
            m3u8_by_series[fid] = set()

        clicked = page.evaluate('''(f) => {
            const item = document.querySelector(`#videoplayer_playlist [fid="${f}"]`);
            if (!item) return false;
            item.click();
            return true;
        }''', fid)

        if not clicked:
            print(f'Серия fid={fid} не найдена')
            continue

        page.wait_for_timeout(4000)

    # Формируем результат
    results = []
    for fid in range(counts['count']):
        title = counts['texts'][fid] or f'Серия {fid + 1}'
        urls = list(m3u8_by_series.get(fid, []))
        distinct = list(dict.fromkeys(urls))
        master = [u for u in distinct if 'master-v' in u or 'index-v' not in u]
        best = master[0] if master else (distinct[0] if distinct else None)
        results.append({
            "title": title,
            "episode_number": fid + 1,
            "id": str(fid),
            "m3u8": best,
            "qualities": {quality: {"default": distinct}},
            "subtitle": ""
        })
        print(f'[{title}] -> {best}')

    return [{
        "title": "Сезон 1",
        "season_number": 1,
        "episodes": results
    }]


# ============ Общая функция выбора качества ============

def select_quality(page, quality):
    quality_map = {'360p': '1', '720p': '2', '1080p': '3'}
    f2id = quality_map.get(quality)

    quality_changed = page.evaluate('''() => {
        const qualityBtn = document.querySelector('[fid="1"]');
        if (!qualityBtn) return { ok: false, reason: 'quality button not found' };
        qualityBtn.click();
        return { ok: true, clicked: true };
    }''')
    print('Клик по кнопке качества:', quality_changed)

    page.wait_for_timeout(800)

    quality_selected = page.evaluate('''(f2) => {
        const el = document.querySelector(`[f2id="${f2}"]`);
        if (!el) return { ok: false, reason: `f2id=${f2} not found` };
        el.click();
        return { ok: true, clicked: true };
    }''', f2id)
    print(f'Выбор качества {quality} (f2id={f2id}):', quality_selected)

    page.wait_for_timeout(3000)


# ============ Главная функция ============

def collect_all(url, quality='1080p'):
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
            locale="ru-RU"
        )
        page = context.new_page()

        is_movie = '/film/' in url
        raw_bodies = []

        # Сетевой перехват /vod/ включаем только для сериалов
        if not is_movie:
            def on_response(response):
                if '/vod/' in response.url:
                    try:
                        text = response.text()
                        if text:
                            raw_bodies.append(text)
                    except Exception:
                        pass
            page.on('response', on_response)

        print('Загрузка страницы и авторизация...')
        authorize(page, url)
        page.wait_for_timeout(4000)

        final_seasons = []

        if is_movie:
            # === ФИЛЬМ ===
            final_seasons = parse_film(page, quality)
        else:
            # === СЕРИАЛ: проверяем наличие сезонов ===
            season_tabs = page.locator('text=/\\d+\\s+сезон/i')
            season_count = season_tabs.count()
            print(f'Вкладок сезонов на странице: {season_count}')

            if season_count > 0:
                # Метод parser2.js (с сезонами)
                print('Обнаружены сезоны. Используем метод parser2.js...')
                final_seasons = parse_serial_with_seasons(page, raw_bodies)
            else:
                # Метод collect_m3u8.js (без сезонов)
                print('Сезоны не обнаружены. Используем метод collect_m3u8.js...')
                final_seasons = parse_serial_no_seasons(page, quality)

        clean_title = 'Без названия'
        try:
            title = page.title()
            clean_title = re.sub(r'\s*смотреть онлайн\s*$', '', title, flags=re.I).strip()
        except Exception:
            pass

        browser.close()

        return {
            "title": clean_title,
            "url": url,
            "quality": quality,
            "seasons": final_seasons
        }


# ============ Веб-сервер (FastAPI) с защитой токеном ============

app = FastAPI()
security = HTTPBearer()


def verify_token(credentials: HTTPAuthorizationCredentials = Depends(security)):
    if not API_TOKEN:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, 
            detail="API_TOKEN не задан на сервере"
        )
    # credentials.credentials содержит только сам токен, без префикса "Bearer"
    if credentials.credentials != API_TOKEN:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, 
            detail="Неверный или отсутствующий токен"
        )

# Передаем verify_token в Depends. Переменная authorization больше не нужна !
@app.get("/parse")
def parse_endpoint(
    url: str, 
    quality: str = "1080p", 
    _token: HTTPAuthorizationCredentials = Depends(verify_token)
):
    try:
        result = collect_all(url, quality)
        return result
    except Exception as e:
        logger.error("Ошибка парсинга URL=%s quality=%s: %s", url, quality, e)
        logger.error("Traceback:\n%s", traceback.format_exc())
        raise HTTPException(status_code=500, detail=str(e))



# ============ Запуск ============

if __name__ == "__main__":
    if len(sys.argv) > 1:
        # CLI-режим: python collect_all.py <URL> [quality]
        url = sys.argv[1]
        quality = sys.argv[2] if len(sys.argv) > 2 else '1080p'

        if quality not in ('360p', '720p', '1080p'):
            print('Использование: python collect_all.py <URL> [quality]')
            print('quality: 360p | 720p | 1080p (по умолчанию 1080p)')
            sys.exit(1)

        try:
            result = collect_all(url, quality)
            print(json.dumps(result, ensure_ascii=False, indent=2))
        except Exception as e:
            print(f"ERROR: {e}", file=sys.stderr)
            sys.exit(1)
    else:
        # Серверный режим: python collect_all.py
        port = int(os.environ.get("PORT", 3000))
        uvicorn.run(app, host="0.0.0.0", port=port)
