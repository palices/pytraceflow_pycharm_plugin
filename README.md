# Pytraceflow Breakpoints
[![Version 1.0.0](https://img.shields.io/badge/version-1.0.0-blue)](#)
[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](#)
[![PyCharm 2024.3+](https://img.shields.io/badge/PyCharm-2024.3%2B-000?logo=pycharm&logoColor=white)](#)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-plugin-4455ff?logo=intellijidea&logoColor=white)](#)
[![Build with Gradle](https://img.shields.io/badge/build-Gradle-02303A?logo=gradle)](#)

Complemento para PyCharm que muestra un icono en el gutter de líneas con breakpoints de Python y abre un panel interactivo con el flujo de ejecución generado por Pytraceflow. Detecta el callable asociado a la línea, vincula los bloques de ejecución del JSON y permite filtrarlos y explorarlos en detalle (entradas, salidas, memoria, errores y llamadas hijas).

## Características
- Icono de marcador de línea cuando la línea tiene un breakpoint en archivos `.py`.
- Popup con árbol de llamadas y panel de detalles (HTML) para cada bloque de traza.
- Filtro por nombre de callable o label `called`, selección automática del bloque que coincide con el callable de la línea.
- Botón **Generate Pytraceflow json** que construye el comando desde la Run Configuration activa (Python) y lo ejecuta en segundo plano.
- Resolución automática del archivo de traza (`pytraceflow.json`, `ptf.json` o el que especifiques en el comando).
- Estilos pensados para temas claro/oscuro de la IDE.

## Requisitos
- PyCharm Community/Professional 2024.3–253.* (según `plugin.xml`).
- JDK 21 (la toolchain está configurada en Gradle).
- Un entorno de Python con el script/CLI `pytraceflow.py` accesible en tu proyecto o en el `PATH`.

## Instalación
1. Construir el plugin:
   ```powershell
   .\gradlew buildPlugin
   ```
2. El ZIP quedará en `build/distributions/` (nombre similar a `Pytraceflow_Breakpoints-1.0.0.zip`).
3. En PyCharm: `Settings/Preferences → Plugins → Install plugin from disk...` y selecciona el ZIP.
4. Reinicia la IDE cuando lo pida.

### Probar en modo sandbox (desarrollo)
```powershell
.\gradlew runIde
```
Abrirá una instancia de PyCharm con el plugin cargado para pruebas locales.

## Cómo usarlo
1. Abre tu proyecto Python y define una Run/Debug Configuration para el script que quieres perfilar.
2. Genera la traza de Pytraceflow (botón del popup o manualmente):
   ```bash
   python pytraceflow.py -s ruta/a/tu_script.py -o pytraceflow.json --with-memory --flush-interval 1.0
   ```
   Otras variantes soportadas por el popup (idénticas a la ayuda in‑app):
   ```bash
   python pytraceflow.py -s samples/basic/basic_sample.py --flush-interval 0 --skip-inputs
   python pytraceflow.py -s samples/basic/basic_sample.py --with-memory --no-tracemalloc
   python pytraceflow.py -s samples/basic/basic_sample.py --export-otlp-endpoint http://localhost:4318/v1/traces
   ```
3. Coloca un breakpoint en una línea Python. El icono amarillo aparecerá en el gutter.
4. Haz clic en el icono para abrir el popup: podrás buscar por callable/called, navegar el árbol y ver detalles (entradas/salidas, errores, memoria antes/después, duración y llamadas hijas).
5. Si ejecutas el botón **Generate Pytraceflow json**, el plugin intentará:
   - Construir el comando a partir de la Run Configuration Python activa.
   - Ejecutarlo en el directorio del proyecto.
   - Cargar el JSON resultante (`pytraceflow.json`, `ptf.json` o la ruta explícita del comando).

## Formato esperado del JSON
`pytraceflow.json` puede ser un objeto o una lista de objetos con los campos:
- `id`, `callable`, `module`, `called`, `caller`, `instance_id`, `duration_ms`
- `memory_before` / `memory_after` con `py_tracemalloc_current` y `py_tracemalloc_peak`
- `inputs`, `inputs_after`, `output`, `error`
- `calls`: lista recursiva de bloques hijos

## Problemas frecuentes
- No se muestra el icono: confirma que la línea tiene un breakpoint activo y el archivo termina en `.py`.
- Popup sin datos: coloca el JSON en la raíz del proyecto con nombre `pytraceflow.json` o selecciona la ruta en el campo de comando y pulsa **Refresh**.
- Comando vacío en el popup: selecciona una Run Configuration de tipo Python; otras configuraciones no generan el comando.

## Comandos útiles
- Ejecutar pruebas: `.\gradlew test`
- Construir el plugin: `.\gradlew buildPlugin`
- Lanzar IDE de prueba: `.\gradlew runIde`

## Licencia
No se ha declarado una licencia en este repositorio; define una antes de distribuir el plugin.
