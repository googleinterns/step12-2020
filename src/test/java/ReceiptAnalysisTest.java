// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.sps;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.api.gax.rpc.StatusCode.Code;
import com.google.cloud.language.v1.ClassificationCategory;
import com.google.cloud.language.v1.ClassifyTextRequest;
import com.google.cloud.language.v1.ClassifyTextResponse;
import com.google.cloud.language.v1.Document;
import com.google.cloud.language.v1.Document.Type;
import com.google.cloud.language.v1.LanguageServiceClient;
import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.EntityAnnotation;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Image;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ByteString;
import com.google.rpc.Status;
import com.google.sps.data.AnalysisResults;
import com.google.sps.servlets.ReceiptAnalysis;
import com.google.sps.servlets.ReceiptAnalysis.ReceiptAnalysisException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@PowerMockIgnore("jdk.internal.reflect.*")
@RunWith(PowerMockRunner.class)
@PrepareForTest(
    {ImageAnnotatorClient.class, LanguageServiceClient.class, ReceiptAnalysis.class, URL.class})
public final class ReceiptAnalysisTest {
  private static final ByteString IMAGE_BYTES = ByteString.copyFromUtf8("byte string");
  private static final Optional<String> RAW_TEXT = Optional.of("raw text");

  private static final String GENERAL_CATEGORY_NAME = "General";
  private static final String BROADER_CATEGORY_NAME = "Broader";
  private static final String SPECIFIC_CATEGORY_NAME = "Specific";
  private static final String CATEGORY_NAME =
      "/" + GENERAL_CATEGORY_NAME + " & " + BROADER_CATEGORY_NAME + "/" + SPECIFIC_CATEGORY_NAME;

  private static final ImmutableSet<String> CATEGORIES =
      ImmutableSet.of(GENERAL_CATEGORY_NAME, BROADER_CATEGORY_NAME, SPECIFIC_CATEGORY_NAME);

  private static final Optional<String> STORE = Optional.of("Google");
  private static final float LOGO_CONFIDENCE = 0.8f;
  private static final float LOGO_CONFIDENCE_BELOW_THRESHOLD = 0.2f;

  private static final Instant INSTANT = Instant.parse("2020-05-08T00:00:00Z");
  private static final Optional<Long> TIMESTAMP = Optional.of(Long.valueOf(INSTANT.toEpochMilli()));
  private static final Instant INSTANT_IN_1900S = Instant.parse("1999-05-08T00:00:00Z");
  private static final Optional<Long> TIMESTAMP_IN_1900S =
      Optional.of(Long.valueOf(INSTANT_IN_1900S.toEpochMilli()));

  private static final double PRICE_VALUE = 12.77;
  private static final Optional<Double> PRICE = Optional.of(Double.valueOf(PRICE_VALUE));

  private URL url;
  private ImageAnnotatorClient imageClient;
  private LanguageServiceClient languageClient;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);

    url = mock(URL.class);
    InputStream inputStream = new ByteArrayInputStream(IMAGE_BYTES.toByteArray());
    when(url.openStream()).thenReturn(inputStream);

    imageClient = mock(ImageAnnotatorClient.class);
    mockStatic(ImageAnnotatorClient.class);
    when(ImageAnnotatorClient.create()).thenReturn(imageClient);

    languageClient = mock(LanguageServiceClient.class);
    mockStatic(LanguageServiceClient.class);
    when(LanguageServiceClient.create()).thenReturn(languageClient);
  }

  @Test
  public void analyzeImageAt_url_returnsAnalysisResults()
      throws IOException, ReceiptAnalysisException {
    stubAnnotationResponse(LOGO_CONFIDENCE, RAW_TEXT.get());
    stubTextClassification();
    ImmutableList<AnnotateImageRequest> imageRequests = createImageRequest();
    ClassifyTextRequest classifyRequest = createClassifyRequest();

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(RAW_TEXT, results.getRawText());
    Assert.assertEquals(CATEGORIES, results.getCategories());
    Assert.assertEquals(STORE, results.getStore());
    Assert.assertEquals(Optional.empty(), results.getTransactionTimestamp());
    Assert.assertEquals(Optional.empty(), results.getPrice());
    verify(imageClient).batchAnnotateImages(imageRequests);
    verify(languageClient).classifyText(classifyRequest);
  }

  @Test
  public void analyzeImageAt_url_returnsAnalysisResultsWithNoStore()
      throws IOException, ReceiptAnalysisException {
    AnnotateImageResponse imageResponse = createImageResponseWithText(RAW_TEXT.get()).build();
    BatchAnnotateImagesResponse batchResponse =
        BatchAnnotateImagesResponse.newBuilder().addResponses(imageResponse).build();
    when(imageClient.batchAnnotateImages(anyList())).thenReturn(batchResponse);

    stubTextClassification();

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(Optional.empty(), results.getStore());
  }

  @Test
  public void analyzeImageAt_lowConfidenceScore_ignoresLogo()
      throws IOException, ReceiptAnalysisException {
    stubAnnotationResponse(LOGO_CONFIDENCE_BELOW_THRESHOLD, RAW_TEXT.get());
    stubTextClassification();
    ImmutableList<AnnotateImageRequest> imageRequests = createImageRequest();
    ClassifyTextRequest classifyRequest = createClassifyRequest();

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(Optional.empty(), results.getStore());
  }

  @Test
  public void analyzeImageAt_returnsTimestamp() throws IOException, ReceiptAnalysisException {
    String rawTextWithDate = "the date is 05-08-2020";
    stubAnnotationResponse(LOGO_CONFIDENCE, rawTextWithDate);
    stubTextClassification();

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(TIMESTAMP, results.getTransactionTimestamp());
  }

  @Test
  public void analyzeImageAt_dateWithSlashes_returnsTimestamp()
      throws IOException, ReceiptAnalysisException {
    String rawTextWithDateUsingSlashes = "the date is 05/08/2020";
    stubAnnotationResponse(LOGO_CONFIDENCE, rawTextWithDateUsingSlashes);
    stubTextClassification();

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(TIMESTAMP, results.getTransactionTimestamp());
  }

  @Test
  public void analyzeImageAt_dateWithNoLeadingZeros_returnsTimestamp()
      throws IOException, ReceiptAnalysisException {
    String rawTextWithDateNoLeadingZeros = "the date is 5-8-2020";
    stubAnnotationResponse(LOGO_CONFIDENCE, rawTextWithDateNoLeadingZeros);
    stubTextClassification();

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(TIMESTAMP, results.getTransactionTimestamp());
  }

  @Test
  public void analyzeImageAt_dateWithTwoDigitYear_returnsTimestamp()
      throws IOException, ReceiptAnalysisException {
    String rawTextWithDateTwoDigitYear = "the date is 05-08-20";
    stubAnnotationResponse(LOGO_CONFIDENCE, rawTextWithDateTwoDigitYear);
    stubTextClassification();

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(TIMESTAMP, results.getTransactionTimestamp());
  }

  @Test
  public void analyzeImageAt_dateIn1900s_returnsTimestamp()
      throws IOException, ReceiptAnalysisException {
    String rawTextWithDateIn1900s = "the date is 05-08-99";
    stubAnnotationResponse(LOGO_CONFIDENCE, rawTextWithDateIn1900s);
    stubTextClassification();

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(TIMESTAMP_IN_1900S, results.getTransactionTimestamp());
  }

  @Test
  public void analyzeImageAt_singlePrice_returnsPrice()
      throws IOException, ReceiptAnalysisException {
    String rawTextWithPrice = "the price is $" + PRICE_VALUE + " in total";
    stubAnnotationResponse(LOGO_CONFIDENCE, rawTextWithPrice);
    stubTextClassification();

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(PRICE, results.getPrice());
  }

  @Test
  public void analyzeImageAt_priceWithNoDollarSign_returnsPrice()
      throws IOException, ReceiptAnalysisException {
    String rawTextWithPriceNoDollarSign = "the price is " + PRICE_VALUE + " in total";
    stubAnnotationResponse(LOGO_CONFIDENCE, rawTextWithPriceNoDollarSign);
    stubTextClassification();

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(PRICE, results.getPrice());
  }

  @Test
  public void analyzeImageAt_multiplePrices_returnsLargestPrice()
      throws IOException, ReceiptAnalysisException {
    String rawTextWithMultiplePrices = "the items cost $8.99, $2.79, and $1.99, so the total is $"
        + PRICE_VALUE + " after the $1.00 discount";
    stubAnnotationResponse(LOGO_CONFIDENCE, rawTextWithMultiplePrices);
    stubTextClassification();

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(PRICE, results.getPrice());
  }

  @Test
  public void analyzeImageAt_dateAndPrice_returnsTimestampAndPrice()
      throws IOException, ReceiptAnalysisException {
    String rawTextWithDateAndPrice = "the date is 05-08-2020 and the total is " + PRICE_VALUE;
    stubAnnotationResponse(LOGO_CONFIDENCE, rawTextWithDateAndPrice);
    stubTextClassification();

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(TIMESTAMP, results.getTransactionTimestamp());
    Assert.assertEquals(PRICE, results.getPrice());
  }

  @Test
  public void analyzeImageAt_emptyBatchResponse_returnsEmptyAnalysisResults()
      throws IOException, ReceiptAnalysisException {
    BatchAnnotateImagesResponse batchResponse = BatchAnnotateImagesResponse.newBuilder().build();
    when(imageClient.batchAnnotateImages(anyList())).thenReturn(batchResponse);

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(Optional.empty(), results.getRawText());
    Assert.assertEquals(ImmutableSet.of(), results.getCategories());
    Assert.assertEquals(Optional.empty(), results.getStore());
  }

  @Test
  public void analyzeImageAt_responseError_returnsEmptyAnalysisResults()
      throws IOException, ReceiptAnalysisException {
    AnnotateImageResponse response =
        AnnotateImageResponse.newBuilder().setError(Status.getDefaultInstance()).build();
    BatchAnnotateImagesResponse batchResponse =
        BatchAnnotateImagesResponse.newBuilder().addResponses(response).build();
    when(imageClient.batchAnnotateImages(anyList())).thenReturn(batchResponse);

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(Optional.empty(), results.getRawText());
    Assert.assertEquals(ImmutableSet.of(), results.getCategories());
    Assert.assertEquals(Optional.empty(), results.getStore());
  }

  @Test
  public void analyzeImageAt_emptyTextAnnotationsListWithoutLogo_returnsEmptyAnalysisResults()
      throws IOException, ReceiptAnalysisException {
    AnnotateImageResponse response = AnnotateImageResponse.newBuilder().build();
    BatchAnnotateImagesResponse batchResponse =
        BatchAnnotateImagesResponse.newBuilder().addResponses(response).build();
    when(imageClient.batchAnnotateImages(anyList())).thenReturn(batchResponse);

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(Optional.empty(), results.getRawText());
    Assert.assertEquals(ImmutableSet.of(), results.getCategories());
    Assert.assertEquals(Optional.empty(), results.getStore());
  }

  @Test
  public void analyzeImageAt_emptyTextAnnotationsListWithLogo_setsLogoOnly()
      throws IOException, ReceiptAnalysisException {
    EntityAnnotation logoAnnotation =
        EntityAnnotation.newBuilder().setDescription(STORE.get()).setScore(LOGO_CONFIDENCE).build();
    AnnotateImageResponse response =
        AnnotateImageResponse.newBuilder().addLogoAnnotations(logoAnnotation).build();
    BatchAnnotateImagesResponse batchResponse =
        BatchAnnotateImagesResponse.newBuilder().addResponses(response).build();
    when(imageClient.batchAnnotateImages(anyList())).thenReturn(batchResponse);

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(Optional.empty(), results.getRawText());
    Assert.assertEquals(ImmutableSet.of(), results.getCategories());
    Assert.assertEquals(STORE, results.getStore());
  }

  @Test
  public void analyzeImageAt_imageRequestFailure_returnsEmptyAnalysisResults()
      throws IOException, ReceiptAnalysisException {
    StatusCode statusCode = GrpcStatusCode.of(io.grpc.Status.INTERNAL.getCode());
    ApiException clientException = new ApiException(null, statusCode, false);
    when(imageClient.batchAnnotateImages(anyList())).thenThrow(clientException);

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(Optional.empty(), results.getRawText());
    Assert.assertEquals(ImmutableSet.of(), results.getCategories());
    Assert.assertEquals(Optional.empty(), results.getStore());
  }

  @Test
  public void analyzeImageAt_textRequestFailure_returnsEmptyCategories()
      throws IOException, ReceiptAnalysisException {
    EntityAnnotation annotation =
        EntityAnnotation.newBuilder().setDescription(RAW_TEXT.get()).build();
    AnnotateImageResponse imageResponse =
        AnnotateImageResponse.newBuilder().addTextAnnotations(annotation).build();
    BatchAnnotateImagesResponse batchResponse =
        BatchAnnotateImagesResponse.newBuilder().addResponses(imageResponse).build();
    when(imageClient.batchAnnotateImages(anyList())).thenReturn(batchResponse);

    StatusCode statusCode = GrpcStatusCode.of(io.grpc.Status.INTERNAL.getCode());
    ApiException clientException = new ApiException(null, statusCode, false);
    when(languageClient.classifyText(any(ClassifyTextRequest.class))).thenThrow(clientException);

    AnalysisResults results = ReceiptAnalysis.analyzeImageAt(url);

    Assert.assertEquals(ImmutableSet.of(), results.getCategories());
  }

  private void stubAnnotationResponse(float confidenceScore, String rawText) {
    EntityAnnotation logoAnnotation =
        EntityAnnotation.newBuilder().setDescription(STORE.get()).setScore(confidenceScore).build();
    AnnotateImageResponse imageResponse =
        createImageResponseWithText(rawText).addLogoAnnotations(logoAnnotation).build();
    BatchAnnotateImagesResponse batchResponse =
        BatchAnnotateImagesResponse.newBuilder().addResponses(imageResponse).build();
    when(imageClient.batchAnnotateImages(anyList())).thenReturn(batchResponse);
  }

  private AnnotateImageResponse.Builder createImageResponseWithText(String rawText) {
    EntityAnnotation annotation = EntityAnnotation.newBuilder().setDescription(rawText).build();
    return AnnotateImageResponse.newBuilder().addTextAnnotations(annotation);
  }

  private void stubTextClassification() {
    ClassificationCategory category =
        ClassificationCategory.newBuilder().setName(CATEGORY_NAME).build();
    ClassifyTextResponse classifyResponse =
        ClassifyTextResponse.newBuilder().addCategories(category).build();
    when(languageClient.classifyText(any(ClassifyTextRequest.class))).thenReturn(classifyResponse);
  }

  private ImmutableList<AnnotateImageRequest> createImageRequest() {
    Image image = Image.newBuilder().setContent(IMAGE_BYTES).build();
    ImmutableList<Feature> features =
        ImmutableList.of(Feature.newBuilder().setType(Feature.Type.TEXT_DETECTION).build(),
            Feature.newBuilder().setType(Feature.Type.LOGO_DETECTION).build());
    AnnotateImageRequest imageRequest =
        AnnotateImageRequest.newBuilder().addAllFeatures(features).setImage(image).build();
    return ImmutableList.of(imageRequest);
  }

  private ClassifyTextRequest createClassifyRequest() {
    Document document =
        Document.newBuilder().setContent(RAW_TEXT.get()).setType(Type.PLAIN_TEXT).build();
    return ClassifyTextRequest.newBuilder().setDocument(document).build();
  }
}
