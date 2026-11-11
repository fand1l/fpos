package com.fand1l.radioguard.shell;

interface IShellService {
    // Код транзакції заданий Shizuku: так сервер просить службу завершитись.
    void destroy() = 16777114;

    // Відповідь: перший рядок — код завершення, далі вивід команди.
    String exec(String script) = 1;

    // Порожня відповідь — успіх, інакше текст помилки.
    String stopTethering() = 2;
}
