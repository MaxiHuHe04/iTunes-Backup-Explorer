package me.maxih.itunes_backup_explorer.util;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;

public class CollectionUtils {

    public static <T> Optional<T> find(Collection<T> list, Predicate<T> predicate) {
        for (T item : list) if (predicate.test(item)) return Optional.of(item);
        return Optional.empty();
    }

    private CollectionUtils() {
    }

}
