package zombie.characters.ecs;

import java.util.HashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import zombie.characters.component.FrameKeeperComponent;
import zombie.characters.ecs.componentmods.ECSFrameStep;
import zombie.characters.ecs.componentmods.ECSGameLoadingStateEnter;
import zombie.characters.ecs.componentmods.ECSInGameStateEnter;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoWorld;
import zombie.util.Type;

public interface ECSEntity {
   // pzopt: marker so the game log shows the loose interface was loaded, not the jar's copy (an interface has no static
   // block; the field initialiser runs when the interface is initialised with its first implementing class)
   boolean PZOPT_LOADED = pzoptMarkLoaded();

   private static boolean pzoptMarkLoaded() {
      pzopt.Overrides.onClassLoaded("zombie.characters.ecs.ECSEntity");
      return true;
   }

   HashMap<Class<? extends ECSComponent>, ECSComponent> getECSComponentMap();

   /** pzopt: ecsValuesCache (zombie redesign, f8-zomb2): the map's values in its iteration order, or null (no cache on this entity type). */
   default ECSComponent[] pzoptEcsValues() { // pzopt
      return null; // pzopt
   } // pzopt

   /** pzopt: ecsValuesCache, the map changed. */
   default void pzoptEcsChanged() { // pzopt
   } // pzopt

   default void registerECSComponents() {
      this.setECSComponent(new FrameKeeperComponent());
   }

   default void frameStep() {
      int currentFrame = IsoWorld.instance.getFrameNo();
      FrameKeeperComponent ecsFrameKeeper = this.getFrameKeeper();
      if (ecsFrameKeeper.getFrameNo() == currentFrame) {
         DebugType.General
            .error("Double-update call to ECSEntity.frameStep, at frame %d. ECSEntities must only be updated once per frame.", new Object[]{currentFrame});
         DebugType.General.printStackTrace(LogSeverity.Error, -1, null, new Object[0]);
         throw new IllegalStateException(String.format("Double-update call at frame %d. ECSEntities must only be updated once per frame.", currentFrame));
      }

      ecsFrameKeeper.setFrameNo(currentFrame);
      this.visitAllComponents(ECSFrameStep.class, ECSFrameStep::frameStep);
   }

   private FrameKeeperComponent getFrameKeeper() {
      return this.getECSComponent(FrameKeeperComponent.class);
   }

   default int getFrameNo() {
      return this.getFrameKeeper().getFrameNo();
   }

   default void onInGameStateEnter() {
      this.visitAllComponents(ECSInGameStateEnter.class, ECSInGameStateEnter::onInGameStateEnter);
   }

   default void onGameLoadingStateEnter() {
      this.visitAllComponents(ECSGameLoadingStateEnter.class, ECSGameLoadingStateEnter::onGameLoadingStateEnter);
   }

   default <ComponentType extends ECSComponent> ComponentType getECSComponent(Class<ComponentType> componentTypeClass) {
      checkParameterNotNull(componentTypeClass, "componentTypeClass");
      ComponentType foundComponentRaw = this.tryGetECSComponent(componentTypeClass);
      if (foundComponentRaw == null) {
         throw new IllegalStateException("Entity has no Component of type: " + componentTypeClass.getSimpleName());
      } else {
         return foundComponentRaw;
      }
   }

   default <ComponentType extends ECSComponent> void setECSComponent(ComponentType component) {
      checkParameterNotNull(component, "component");
      if (!this.hasECSComponent(component)) {
         this.removeECSComponent(component.getECSClass());
         this.getECSComponentMapInternal().put(component.getECSClass(), component);
         this.pzoptEcsChanged(); // pzopt: ecsValuesCache
         component.setECSOwnerEntity(this);
      }
   }

   default <ComponentType extends ECSComponent> void removeECSComponent(ComponentType component) {
      checkParameterNotNull(component, "component");
      if (this.hasECSComponent(component)) {
         this.removeECSComponent(component.getECSClass());
      }
   }

   default <ComponentType extends ECSComponent> void removeECSComponent(Class<ComponentType> componentClass) {
      checkParameterNotNull(componentClass, "componentClass");
      ComponentType component = this.tryGetECSComponent(componentClass);
      if (component != null) {
         this.getECSComponentMapInternal().remove(component.getECSClass());
         this.pzoptEcsChanged(); // pzopt: ecsValuesCache
         component.setECSOwnerEntity(null);
      }
   }

   default <ComponentType extends ECSComponent> ComponentType tryGetECSComponent(Class<ComponentType> componentTypeClass) {
      // pzopt: ecsLookupFast. The hot lookup (every getStateMachine / getActionContext / getVariable of every character goes
      // through here): the memoised key, one map probe, an isInstance check; no null-parameter throw paths, no Class.cast.
      if (componentTypeClass != null && pzopt.Overrides.enabled() && pzopt.Config.ECS_LOOKUP_FAST) {
         ECSComponent found = this.getECSComponentMap().get(ECSComponent.getECSClass(componentTypeClass));
         return componentTypeClass.isInstance(found) ? (ComponentType)found : null;
      }

      checkParameterNotNull(componentTypeClass, "componentTypeClass");
      return (ComponentType)Type.tryCastTo(this.getECSComponentMapInternal().get(ECSComponent.getECSClass(componentTypeClass)), componentTypeClass);
   }

   default boolean hasECSComponent(Class<? extends ECSComponent> componentTypeClass) {
      checkParameterNotNull(componentTypeClass, "componentTypeClass");
      return this.tryGetECSComponent(componentTypeClass) != null;
   }

   default boolean hasECSComponent(ECSComponent component) {
      checkParameterNotNull(component, "component");
      return this.tryGetECSComponent(component.getECSClass()) == component;
   }

   default <ST> void visitAllComponents(Class<? extends ST> instanceOf, Consumer<ST> visitor) {
      ECSComponent[] pzoptValues = this.pzoptEcsValues(); // pzopt: ecsValuesCache, the same values in the same order without an iterator a call
      if (pzoptValues != null) { // pzopt
         for (ECSComponent c : pzoptValues) { // pzopt
            ST converted = (ST)Type.tryCastTo(c, instanceOf); // pzopt
            if (converted != null) { // pzopt
               visitor.accept(converted); // pzopt
            } // pzopt
         } // pzopt
         return; // pzopt
      } // pzopt
      for (ECSComponent c : this.getECSComponentMapInternal().values()) {
         ST converted = (ST)Type.tryCastTo(c, instanceOf);
         if (converted != null) {
            visitor.accept(converted);
         }
      }
   }

   default <ST, P1> void visitAllComponents(Class<? extends ST> instanceOf, BiConsumer<ST, P1> visitor, P1 param1) {
      ECSComponent[] pzoptValues = this.pzoptEcsValues(); // pzopt: ecsValuesCache
      if (pzoptValues != null) { // pzopt
         for (ECSComponent c : pzoptValues) { // pzopt
            ST converted = (ST)Type.tryCastTo(c, instanceOf); // pzopt
            if (converted != null) { // pzopt
               visitor.accept(converted, param1); // pzopt
            } // pzopt
         } // pzopt
         return; // pzopt
      } // pzopt
      for (ECSComponent c : this.getECSComponentMapInternal().values()) {
         ST converted = (ST)Type.tryCastTo(c, instanceOf);
         if (converted != null) {
            visitor.accept(converted, param1);
         }
      }
   }

   private HashMap<Class<? extends ECSComponent>, ECSComponent> getECSComponentMapInternal() {
      HashMap<Class<? extends ECSComponent>, ECSComponent> componentMap = this.getECSComponentMap();
      if (componentMap == null) {
         throw new IllegalStateException("Entity has no component map.");
      } else {
         return componentMap;
      }
   }

   static void checkParameterNotNull(Object parameter, String parameterName) {
      if (parameter == null) {
         throw new IllegalArgumentException("Parameter " + parameterName + " cannot be null.");
      }
   }
}
