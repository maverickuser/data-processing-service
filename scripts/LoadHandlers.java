/**
 * Loads each named handler class from the class path, as Lambda's runtime would, and checks that it
 * is a Lambda handler with the public no-argument constructor Lambda calls. The constructors are
 * not run: they start the application, which needs a database and AWS settings.
 */
class LoadHandlers {

  public static void main(String[] handlers) throws Exception {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    Class<?> requestHandler =
        loader.loadClass("com.amazonaws.services.lambda.runtime.RequestHandler");
    Class<?> streamHandler =
        loader.loadClass("com.amazonaws.services.lambda.runtime.RequestStreamHandler");
    for (String name : handlers) {
      Class<?> handler = Class.forName(name, true, loader);
      if (!requestHandler.isAssignableFrom(handler) && !streamHandler.isAssignableFrom(handler)) {
        throw new IllegalStateException(name + " is not a Lambda handler");
      }
      handler.getConstructor();
      System.out.println("Loaded " + name);
    }
  }
}
