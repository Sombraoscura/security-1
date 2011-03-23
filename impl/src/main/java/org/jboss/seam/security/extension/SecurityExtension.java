package org.jboss.seam.security.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.util.Nonbinding;

import org.jboss.seam.security.AuthorizationException;
import org.jboss.seam.security.SecurityDefinitionException;
import org.jboss.seam.security.annotations.Secures;
import org.jboss.seam.security.annotations.SecurityBindingType;
import org.jboss.seam.solder.reflection.annotated.AnnotatedTypeBuilder;
import org.jboss.seam.solder.reflection.annotated.InjectableMethod;

/**
 * Extension for typesafe security annotations
 * 
 * @author Shane Bryzak
 *
 */
public class SecurityExtension implements Extension
{
   private BeanManager beanManager;
   
   class Authorizer
   {
      private Annotation binding;
      private Map<Method,Object> memberValues = new HashMap<Method,Object>();
      
      private AnnotatedMethod<?> implementationMethod;
      private Bean<?> targetBean;
      
      private InjectableMethod<?> injectableMethod;
      
      public Authorizer(Annotation binding, AnnotatedMethod<?> implementationMethod)
      {
         this.binding = binding;
         this.implementationMethod = implementationMethod;

         try
         {
            for (Method m : binding.annotationType().getDeclaredMethods())
            {
               if (m.isAnnotationPresent(Nonbinding.class)) continue;
               memberValues.put(m, m.invoke(binding));
            }
         }
         catch (InvocationTargetException ex)
         {
            throw new SecurityDefinitionException("Error reading security binding members", ex);
         }
         catch (IllegalAccessException ex)
         {
            throw new SecurityDefinitionException("Error reading security binding members", ex);
         }
      }
      
      public void authorize()
      {
         if (targetBean == null) 
         {
            lookupTargetBean();
         }         
         
         CreationalContext<?> cc = beanManager.createCreationalContext(targetBean);
         
         Object reference = beanManager.getReference(targetBean, 
               implementationMethod.getJavaMember().getDeclaringClass(), cc);         

         Object result = injectableMethod.invoke(reference, cc, null);
         
         if (result.equals(Boolean.FALSE))
         {
            throw new AuthorizationException("Authorization check failed");
         }
      }
      
      @SuppressWarnings({ "unchecked", "rawtypes" })
      private synchronized void lookupTargetBean()
      {
         if (targetBean == null)
         {
            Method m = implementationMethod.getJavaMember();
            
            Set<Bean<?>> beans = beanManager.getBeans(m.getDeclaringClass());
            if (beans.size() == 1)
            {
               targetBean = beans.iterator().next();
            }
            else if (beans.isEmpty())
            {
               throw new IllegalStateException("Exception looking up authorizer method bean - " +
               "no beans found for method [" + m.getDeclaringClass() + "." +
               m.getName() + "]");
            }
            else if (beans.size() > 1)
            {
               throw new IllegalStateException("Exception looking up authorizer method bean - " +
                     "multiple beans found for method [" + m.getDeclaringClass().getName() + "." +
                     m.getName() + "]");
            }            
            
            injectableMethod = new InjectableMethod(implementationMethod, targetBean, beanManager);
         }
      }
      
      public boolean matchesBinding(Annotation annotation) 
      {
         if (!annotation.annotationType().equals(binding.annotationType()))
         {
            return false;
         }
         
         for (Method m : annotation.annotationType().getDeclaredMethods())
         {
            if (m.isAnnotationPresent(Nonbinding.class)) continue;
            
            if (!memberValues.containsKey(m))
            {
               return false;
            }
            
            try
            {
               Object value = m.invoke(annotation);
               if (!memberValues.get(m).equals(value))
               {
                  return false;
               }
            }
            catch (InvocationTargetException ex)
            {
               throw new SecurityDefinitionException("Error reading security binding members", ex);
            }
            catch (IllegalAccessException ex)
            {
               throw new SecurityDefinitionException("Error reading security binding members", ex);
            }
         }
         
         return true;
      }
      
      public Method getImplementationMethod()
      {
         return implementationMethod.getJavaMember();
      }
      
      @Override
      public boolean equals(Object value)
      {
         return false;
      }
      
      @Override
      public int hashCode()
      {
         return 0;
      }
   }
   
   /**
    * Contains all known authorizers
    */
   private Set<Authorizer> authorizers = new HashSet<Authorizer>();
   
   /**
    * Contains all known secured types
    */
   private Set<AnnotatedType<?>> securedTypes = new HashSet<AnnotatedType<?>>();
   
   /**
    * A mapping between a secured method and its authorizers
    */
   private Map<Method,Set<Authorizer>> methodAuthorizers = new HashMap<Method,Set<Authorizer>>();
   
   /**
    * 
    * @param <X>
    * @param event
    * @param beanManager
    */
   public <X> void processAnnotatedType(@Observes ProcessAnnotatedType<X> event, 
         final BeanManager beanManager)
   {
      AnnotatedTypeBuilder<X> builder = null;
      AnnotatedType<X> type = event.getAnnotatedType();
      
      boolean isSecured = false;
      
      // Add the security interceptor to the class if the class is annotated
      // with a security binding type
      for (final Annotation annotation : type.getAnnotations())
      {
         if (annotation.annotationType().isAnnotationPresent(SecurityBindingType.class))
         {
            builder = new AnnotatedTypeBuilder<X>().readFromType(type);
            builder.addToClass(SecurityInterceptorBindingLiteral.INSTANCE);
            isSecured = true;
         }
      }
      
      // If the class isn't annotated with a security binding type, check if
      // any of its methods are, and if so, add the security interceptor to the
      // method
      if (!isSecured)
      {
         for (final AnnotatedMethod<? super X> m : type.getMethods())
         {
            if (m.isAnnotationPresent(Secures.class)) 
            {
               registerAuthorizer(m);
               continue;
            }
            
            for (final Annotation annotation : m.getAnnotations())
            {
               if (annotation.annotationType().isAnnotationPresent(SecurityBindingType.class))
               {
                  if (builder == null)
                  {
                     builder = new AnnotatedTypeBuilder<X>().readFromType(type);
                  }
                  builder.addToMethod(m, SecurityInterceptorBindingLiteral.INSTANCE);
                  isSecured = true;
                  break;
               }
            }
         }         
      }
      
      // If either the bean or any of its methods are secured, register it
      if (isSecured)
      {
         securedTypes.add(type);
      }
      
      if (builder != null)
      {
         event.setAnnotatedType(builder.create());
      }      
   }
   
   public void validateBindings(@Observes AfterBeanDiscovery event, BeanManager beanManager) 
   {
      this.beanManager = beanManager;
      
      for (final AnnotatedType<?> type : securedTypes)
      {
         // Here we simply want to validate that each type that is annotated with
         // one or more security bindings has a valid authorizer for each binding
         
         for (final Annotation annotation : type.getJavaClass().getAnnotations())
         {
            boolean found = false;
            
            if (annotation.annotationType().isAnnotationPresent(SecurityBindingType.class))
            {
               // Validate the authorizer
               for (Authorizer auth : authorizers)
               {
                  if (auth.matchesBinding(annotation))
                  {
                     found = true;
                     break;
                  }
               }
               
               if (!found)
               {
                  event.addDefinitionError(new SecurityDefinitionException("Secured type " +
                        type.getJavaClass().getName() + 
                        " has no matching authorizer method for security binding @" +
                        annotation.annotationType().getName()));
               }
            }
         }
         
         for (final AnnotatedMethod<?> method : type.getMethods())
         {
            for (final Annotation annotation : method.getAnnotations())
            {
               if (annotation.annotationType().isAnnotationPresent(SecurityBindingType.class))
               {
                  registerSecuredMethod(method.getJavaMember());
                  break;
               }
            }            
         }
      }      
      
      // Clear securedTypes, we don't require it any more
      securedTypes.clear();
      securedTypes = null;
   }
   
   /**
    * This method is invoked by the security interceptor to obtain the
    * authorizer stack for a secured method
    * 
    * @param m
    * @return
    */
   public Set<Authorizer> lookupAuthorizerStack(Method m)
   {
      if (!methodAuthorizers.containsKey(m))
      {
         registerSecuredMethod(m);
      }
      
      return methodAuthorizers.get(m);
   }   
   
   void checkAuthorization(Annotation binding)
   {
      boolean authorized = false;
      
      for (Authorizer authorizer : authorizers)
      {
         if (authorizer.matchesBinding(binding))
         {
            authorizer.authorize();
            authorized = true;
         }
      }
      
      if (!authorized)
      {
         throw new AuthorizationException(
               "Failed to process authorization request - no matching authorizer " +
               "method for specified binding type [" + binding.annotationType().getClass().getName() + "]");
      }
   }
   
   protected void registerSecuredMethod(Method method) 
   {
      if (!methodAuthorizers.containsKey(method))
      {
         // Build a list of all security bindings on both the method and its declaring class
         Set<Annotation> bindings = new HashSet<Annotation>();
         
         for (final Annotation annotation : method.getDeclaringClass().getAnnotations())
         {
            if (annotation.annotationType().isAnnotationPresent(SecurityBindingType.class))
            {
               bindings.add(annotation);
            }
         }
         
         for (final Annotation annotation : method.getAnnotations())
         {
            if (annotation.annotationType().isAnnotationPresent(SecurityBindingType.class))
            {
               bindings.add(annotation);
            }
         }
         
         Set<Authorizer> authorizerStack = new HashSet<Authorizer>();
         
         for (Annotation binding : bindings)
         {
            boolean found = false;
            
            // For each security binding, find a valid authorizer
            for (Authorizer authorizer : authorizers)
            {
               if (authorizer.matchesBinding(binding))
               {
                  if (found)
                  {
                     StringBuilder sb = new StringBuilder();
                     sb.append("Matching authorizer methods found: [");
                     sb.append(authorizer.getImplementationMethod().getDeclaringClass().getName());
                     sb.append(".");
                     sb.append(authorizer.getImplementationMethod().getName());
                     sb.append("]");
                     
                     for (Authorizer a : authorizerStack)
                     {
                        if (a.matchesBinding(binding))
                        {
                           sb.append(", [");
                           sb.append(a.getImplementationMethod().getDeclaringClass().getName());
                           sb.append(".");
                           sb.append(a.getImplementationMethod().getName());
                           sb.append("]");                              
                        }
                     }
                     
                     throw new SecurityDefinitionException(
                           "Ambiguous authorizers found for security binding type [@" +
                           binding.annotationType().getName() + "] on method [" +
                           method.getDeclaringClass().getName() + "." +
                           method.getName() + "]. " + sb.toString());
                  }
                  
                  authorizerStack.add(authorizer);
                  found = true;
               }              
            }
            
            if (!found)
            {
               throw new SecurityDefinitionException(
                     "No matching authorizer found for security binding type [@" +
                     binding.annotationType().getName() + "] on method [" +
                     method.getDeclaringClass().getName() + "." +
                     method.getName() + "].");
            }
            
            methodAuthorizers.put(method, authorizerStack);
         }
      }
   }
   
   /**
    * Registers the specified authorizer method (i.e. a method annotated with
    * the @Secures annotation)
    * 
    * @param m
    * @throws IllegalAccessException 
    * @throws InvocationTargetException 
    */
   protected void registerAuthorizer(AnnotatedMethod<?> m) 
   {
      if (!m.getJavaMember().getReturnType().equals(Boolean.class) &&
          !m.getJavaMember().getReturnType().equals(Boolean.TYPE))
      {
         throw new SecurityDefinitionException("Invalid authorizer method [" +
               m.getJavaMember().getDeclaringClass().getName() + "." +
               m.getJavaMember().getName() + "] - does not return a boolean.");
      }
      
      // Locate the binding type
      Annotation binding = null;
      
      for (Annotation a : m.getAnnotations())
      {
         if (a.annotationType().isAnnotationPresent(SecurityBindingType.class))
         {
            if  (binding != null)
            {
               throw new SecurityDefinitionException("Invalid authorizer method [" +
                     m.getJavaMember().getDeclaringClass().getName() + "." +
                     m.getJavaMember().getName() + "] - declares multiple security binding types");
            }
            binding = a;
         }
      }
      
      Authorizer authorizer = new Authorizer(binding, m);
      authorizers.add(authorizer);
   }
}
