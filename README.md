# msklad

Проект для работы с перемещениями товаров между складами.

## Возможности

- Создание перемещений между складами
- Просмотр и поиск перемещений
- Изменение статуса перемещения (черновик, проведено, отменено)

## Создание проекта

Проект создан как локальный git-репозиторий и отправлен на GitHub:

```bash
mkdir msklad && cd msklad
git init -b master
git remote add origin https://github.com/alexyalt/msklad.git
git add .
git commit -m "Initial commit"
git push -u origin master
```

## Требования

- Python 3.10+

## Установка

```bash
git clone <url-репозитория>
cd msklad
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Использование

Инструкции по запуску будут добавлены по мере разработки.

## Структура проекта

```
msklad/
├── README.md
└── .gitignore
```

## Лицензия

Не указана.
