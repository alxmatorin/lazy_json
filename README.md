# JsonBytes

Поиск и замена значений в JSON прямо в `byte[]`, без разбора всего документа в дерево или `Map`.
Поиск возвращает диапазон исходных байт, а правки собирают новый документ, копируя неизменённые участки как есть.
Главный класс — `ru.sber.jsonbytes.JsonBytes`.

## Сборка

Нужны JDK 21+ и Maven:

```sh
mvn test
mvn package
```

## Быстрый старт

```java
import ru.sber.jsonbytes.JsonBytes;
import ru.sber.jsonbytes.Slice;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Example {
    public static void main(String[] args) {
        byte[] source = """
                {"client":{"id":42,"name":"Ann"},"items":[{"id":1},{"id":2}]}
                """.getBytes(UTF_8);
        JsonBytes json = JsonBytes.of(source);

        Slice id = json.find("$client.id");
        System.out.println(id.text()); // 42

        var ids = json.findAll("$items[*].id");
        System.out.println(ids.stream().map(Slice::text).toList()); // [1, 2]

        byte[] updated = json.edit()
                .set("$client.name", "Bob")
                .set("$client.active", true)
                .apply();
        System.out.println(new String(updated, UTF_8));
        // {"client":{"id":42,"name":"Bob","active":true},"items":[{"id":1},{"id":2}]}
    }
}
```

`set` и `apply` возвращают новый `byte[]`. Исходный документ и экземпляр `json` продолжают представлять исходные данные.
Для дальнейшей работы с результатом создайте `JsonBytes.of(updated)`.

## Пути и чтение

| Путь | Что выбирает |
| --- | --- |
| `$` | Весь документ |
| `$client.id` или `$.client.id` | Поле вложенного объекта |
| `$items[0].id` | Поле первого элемента массива |
| `$items[*].id` | Поле каждого элемента массива |
| `$['a.b'].id` | Поле внутри объекта с буквальным ключом `a.b` |
| `$<client.id` | Тот же путь, но корневой объект обходится с конца |

`find` возвращает первый найденный `Slice` или `null`. `findAll` возвращает все совпадения в порядке документа либо пустой список.
`Slice` содержит `doc`, `offset` и `length` без копирования значения. `copy()` копирует его байты, `text()` создаёт строку с исходным JSON: строковые значения остаются в кавычках.

Путь можно подготовить и переиспользовать:

```java
var path = ru.sber.jsonbytes.ElementPath.compile("$items[*].id");
var ids = json.findAll(path);

var clientId = ru.sber.jsonbytes.ElementPath.of("client", "id");
Slice id = json.find(clientId);
Slice sameId = json.find(java.util.List.of("client", "id"));
```

В форме списка каждый элемент — ключ объекта; индексы массивов и `[*]` задаются строковым путём.
Начальный `<` в первом ключе списка также включает обратный обход: `List.of("<client", "id")`.
Для буквального ключа, начинающегося с `<`, используйте путь с кавычками: `$['<client'].id`.

Хинт `$<` полезен, когда нужный корневой ключ находится ближе к концу и значения в хвосте небольшие.
Он меняет направление только на уровне корня: перед чтением ключа его значение приходится целиком пропустить назад.
Поддерживается указанный набор путей, без фильтров, рекурсивного поиска и диапазонов индексов.

## Замена и вставка

Следующие примеры используют `json` из быстрого старта; каждый независимо возвращает свой результат:

```java
byte[] renamed = json.set("$client.name", "Bob"); // Java-строка → JSON-строка
byte[] inserted = json.set("$client.settings.theme", "dark"); // создаёт недостающие объекты
byte[] replaced = json.set("$items[*].id", 0); // заменяет все совпадения

byte[] stringValue = json.set("$items", "[1,2]"); // значение будет "[1,2]"
byte[] arrayValue = json.setRaw("$items", "[1,2]"); // значение будет [1,2]
byte[] rawBytes = json.set("$items", "[1,2]".getBytes(UTF_8)); // готовые JSON-байты
```

Обычные Java-значения (`null`, числа, строки, `Map`, `List`, POJO) сериализуются через `JsonEncoder`.
`byte[]` и аргумент `setRaw` считаются готовым JSON и вставляются без сериализации и проверки.

Несколько правок через `edit()` выполняются общим обходом и одной сборкой результата.
Подготовленный пакет можно применять к разным документам:

```java
var edits = json.edit()
        .set("$client.active", true)
        .set("$client.name", "Bob");

byte[] first = edits.apply();
var other = JsonBytes.of("{\"client\":{\"id\":99}}".getBytes(UTF_8));
byte[] second = edits.applyTo(other);
```

Пакет сохраняет сериализованные значения и переиспользует скомпилированные пути.
Также доступен `apply(List<Edit>)`; `Edit.raw(path, text)` и `Edit.of(path, bytes)` принимают готовый JSON.
Для одинакового пути действует последняя правка. Пересекающиеся пути (например, `$client` и `$client.id`) и несовместимые требования к типу контейнера вызывают `IllegalArgumentException`.

## Сериализация и ограничения

По умолчанию используется Jackson с кэшем на 500 значений: строки сравниваются по значению, остальные объекты — по идентичности.
Не изменяйте объекты, уже переданные этому энкодеру: повторная вставка может взять байты из кэша.
Для изменяемых объектов можно выбрать энкодер без кэша:

```java
var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
var encoder = ru.sber.jsonbytes.JsonEncoder.jackson(mapper);
var document = JsonBytes.of(source, encoder);
```

- Входной документ и сырые вставки должны быть валидным JSON в UTF-8. JsonBytes не является полным валидатором.
- Исходный `byte[]` хранится по ссылке; `bytes()` возвращает тот же массив. Не изменяйте его во время работы с документом или его `Slice`. Не изменяйте также байты подготовленных правок.
- Экземпляр `JsonBytes` не потокобезопасен. Он запоминает смещения просмотренных корневых полей, ускоряя повторные обращения к тому же документу.
- Используйте уникальные ключи объектов. При дубликатах результат может зависеть от направления обхода.
- Можно создавать недостающие поля и промежуточные объекты, но нельзя создавать или расширять массивы через индекс. Запись за границу массива вызывает ошибку; `[*]` над пустым массивом ничего не меняет.
