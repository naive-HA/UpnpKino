package java.util;

import java.util.function.Supplier;
import java.util.stream.Stream;

public final class ServiceLoader<S> implements Iterable<S> {
    public interface Provider<S> extends Supplier<S> {
        Class<? extends S> type();
        S get();
    }
    private ServiceLoader() {
        throw new UnsupportedOperationException("ponyfill");
    }
    public static <S> ServiceLoader<S> load(Class<S> service, ClassLoader loader) {
        throw new UnsupportedOperationException("ponyfill");
    }
    public static <S> ServiceLoader<S> load(Class<S> service) {
        throw new UnsupportedOperationException("ponyfill");
    }
    public static <S> ServiceLoader<S> loadInstalled(Class<S> service) {
        throw new UnsupportedOperationException("ponyfill");
    }
    public Stream<Provider<S>> stream() {
        throw new UnsupportedOperationException("ponyfill");
    }
    @Override
    public Iterator<S> iterator() {
        throw new UnsupportedOperationException("ponyfill");
    }
    public Optional<S> findFirst() {
        throw new UnsupportedOperationException("ponyfill");
    }
    public void reload() {
        throw new UnsupportedOperationException("ponyfill");
    }
}
