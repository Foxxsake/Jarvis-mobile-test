#!/bin/bash
sed -i 's/val res = contactResolver.resolveCommandTarget(plan.actions.first())/val res = contactResolver.resolveCommandTarget(plan.actions.first())\n/g' app/src/test/java/com/example/JarvisEngineTest.kt
