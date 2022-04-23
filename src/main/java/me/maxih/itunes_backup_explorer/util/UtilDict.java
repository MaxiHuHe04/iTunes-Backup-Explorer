package me.maxih.itunes_backup_explorer.util;

import com.dd.plist.*;

import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

public class UtilDict {
    public final NSDictionary dict;

    public UtilDict(NSDictionary dict) {
        this.dict = dict;
    }

    public <T extends NSObject> Optional<T> get(Class<T> type, String... path) {
        if (path.length == 0) return Optional.empty();

        NSDictionary dict = this.dict;
        for (int i = 0; i < path.length - 1; i++) {
            NSObject obj = dict.objectForKey(path[i]);
            if (!(obj instanceof NSDictionary)) return Optional.empty();
            dict = (NSDictionary) obj;
        }

        NSObject obj = dict.objectForKey(path[path.length - 1]);
        if (type.isInstance(obj)) return Optional.of(type.cast(obj));

        return Optional.empty();
    }

    public Optional<UtilDict> getDict(String... path) {
        return get(NSDictionary.class, path).map(UtilDict::new);
    }

    public Optional<NSObject[]> getArray(String... path) {
        return get(NSArray.class, path).map(NSArray::getArray);
    }

    public <T> Optional<Stream<T>> getTypedArrayStream(Class<T> type, String... path) {
        return getArray(path).map(array -> Arrays.stream(array)
                .filter(type::isInstance)
                .map(type::cast)
        );
    }

    public Optional<String> getString(String... path) {
        return get(NSString.class, path).map(NSString::getContent);
    }

    public Optional<Boolean> getBoolean(String... path) {
        return get(NSNumber.class, path).map(NSNumber::boolValue);
    }

    public Optional<Date> getDate(String... path) {
        return get(NSDate.class, path).map(NSDate::getDate);
    }

    public Optional<NSData> getData(String... path) {
        return get(NSData.class, path);
    }

    public <T> void forTypedEntries(Class<T> type, BiConsumer<String, T> consumer) {
        dict.entrySet().stream()
                .filter(entry -> type.isInstance(entry.getValue()))
                .map(entry -> Map.entry(entry.getKey(), type.cast(entry.getValue())))
                .forEach(entry -> consumer.accept(entry.getKey(), entry.getValue()));
    }

    public void put(String key, Object value) {
        this.dict.put(key, value);
    }
}
