package org.example.json.bench;

import java.io.IOException;
import java.util.List;

/**
 * Документы и операции над ними. Документов несколько — итерация {@code i} работает
 * с {@code documents().get(i % n)}, эмулируя поток разных входных сообщений одной формы.
 */
public interface Scenario {

    String name();

    List<byte[]> documents() throws IOException;

    List<Op> ops();
}
