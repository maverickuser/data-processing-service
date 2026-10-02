package com.bondplatform.dataprocessing.architecture.fixture.lambdaedge.application;

import com.amazonaws.services.lambda.runtime.Context;

/** Breaks the Lambda-edge rule: a use case that knows about Lambda. */
public class UseCaseWithLambdaType {

  int remainingMillis(Context context) {
    return context.getRemainingTimeInMillis();
  }
}
