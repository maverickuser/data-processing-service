package com.bondplatform.dataprocessing.lambda;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import org.jspecify.annotations.Nullable;

/** A Lambda invocation context with fixed values, for tests. */
final class FixedLambdaContext implements Context {

  @Override
  public String getAwsRequestId() {
    return "test-request";
  }

  @Override
  public String getLogGroupName() {
    return "test-log-group";
  }

  @Override
  public String getLogStreamName() {
    return "test-log-stream";
  }

  @Override
  public String getFunctionName() {
    return "data-processing-service-api";
  }

  @Override
  public String getFunctionVersion() {
    return "1";
  }

  @Override
  public String getInvokedFunctionArn() {
    return "arn:aws:lambda:ap-south-1:000000000000:function:data-processing-service-api";
  }

  @Override
  public @Nullable CognitoIdentity getIdentity() {
    return null;
  }

  @Override
  public @Nullable ClientContext getClientContext() {
    return null;
  }

  @Override
  public int getRemainingTimeInMillis() {
    return 29_000;
  }

  @Override
  public int getMemoryLimitInMB() {
    return 1024;
  }

  @Override
  public @Nullable LambdaLogger getLogger() {
    return null;
  }
}
