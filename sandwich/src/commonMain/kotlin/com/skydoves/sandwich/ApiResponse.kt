/*
 * Designed and developed by 2020 skydoves (Jaewoong Eum)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.skydoves.sandwich

import com.skydoves.sandwich.mappers.ApiResponseFailureMapper
import com.skydoves.sandwich.mappers.ApiResponseFailureSuspendMapper
import com.skydoves.sandwich.operators.ApiResponseOperator
import com.skydoves.sandwich.operators.ApiResponseSuspendOperator
import kotlinx.coroutines.launch

/**
 * @author skydoves (Jaewoong Eum)
 *
 * ApiResponse is an interface for constructing standard responses from the retrofit call.
 */
public sealed interface ApiResponse<out T> {

  /**
   * @author skydoves (Jaewoong Eum)
   *
   * API Success response class from OkHttp request call.
   * The [data] is a nullable generic type. (A response without data)

   * @property data The de-serialized response body of a successful data.
   */
  public data class Success<T>(public val data: T, public val tag: Any? = null) : ApiResponse<T>

  /**
   * @author skydoves (Jaewoong Eum)
   *
   * API Failure response class from OkHttp request call.
   * There are two subtypes: [ApiResponse.Failure.Error] and [ApiResponse.Failure.Exception].
   */
  public sealed interface Failure<T> : ApiResponse<T> {
    /**
     * API response error case.
     * API communication conventions do not match or applications need to handle errors.
     * e.g., internal server error.
     */
    public data class Error<T>(public val payload: Any?) : Failure<T>

    /**
     * @author skydoves (Jaewoong Eum)
     *
     * API request Exception case.
     * An unexpected exception occurs while creating requests or processing an response in the client side.
     * e.g., network connection error, timeout.
     *
     * @param exception An throwable exception.
     *
     * @property message The localized message from the exception.
     */
    public data class Exception<T>(val exception: Throwable) : Failure<T> {
      val message: String? = exception.message
    }

    /**
     * @author skydoves (Jaewoong Eum)
     *
     * A customizable failure case.
     * For any reasons, if you need to design your own error API models, you can build your own
     * failure models by extending [Cause].
     *
     * @property payload A payload that [Cause] can contain.
     */
    public abstract class Cause : Failure<Nothing> {
      public abstract val payload: Any?
    }
  }

  public companion object {
    /**
     * @author skydoves (Jaewoong Eum)
     *
     * [Failure] factory function. Only receives [Throwable] as an argument.
     *
     * @param ex A throwable.
     *
     * @return A [ApiResponse.Failure.Exception] based on the throwable.
     */
    public fun <T> exception(ex: Throwable): Failure.Exception<T> =
      Failure.Exception<T>(ex).apply { operate().mapFailure() }

    /**
     * @author skydoves (Jaewoong Eum)
     *
     * ApiResponse Factory.
     *
     * Create an [ApiResponse] from the given executable [f].
     *
     * If the [f] doesn't throw any exceptions, it creates [ApiResponse.Success].
     * If the [f] throws an exception, it creates [ApiResponse.Failure.Exception].
     */
    public inline fun <reified T> of(tag: Any? = null, crossinline f: () -> T): ApiResponse<T> {
      return try {
        val result = f()
        Success(
          data = result,
          tag = tag,
        )
      } catch (e: Exception) {
        exception(e)
      }.operate().mapFailure()
    }

    /**
     * @author skydoves (Jaewoong Eum)
     *
     * ApiResponse Factory.
     *
     * Create an [ApiResponse] from the given executable [f].
     *
     * If the [f] doesn't throw any exceptions, it creates [ApiResponse.Success].
     * If the [f] throws an exception, it creates [ApiResponse.Failure.Exception].
     */
    @SuspensionFunction
    public suspend inline fun <reified T> suspendOf(
      tag: Any? = null,
      crossinline f: suspend () -> T,
    ): ApiResponse<T> {
      val result = f()
      return of(tag = tag) { result }
    }

    /**
     * @author skydoves (Jaewoong Eum)
     *
     * Operates if there is a global [com.skydoves.sandwich.operators.SandwichOperator]
     * which operates on [ApiResponse]s globally on each response and returns the target [ApiResponse].
     *
     * @return [ApiResponse] A target [ApiResponse].
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T> ApiResponse<T>.operate(): ApiResponse<T> = apply {
      val globalOperators = SandwichInitializer.sandwichOperators
      globalOperators.forEach { globalOperator ->
        if (globalOperator is ApiResponseOperator<*>) {
          operator(globalOperator as ApiResponseOperator<T>)
        } else if (globalOperator is ApiResponseSuspendOperator<*>) {
          val scope = SandwichInitializer.sandwichScope
          scope.launch {
            suspendOperator(globalOperator as ApiResponseSuspendOperator<T>)
          }
        }
      }
    }

    @Suppress("UNCHECKED_CAST")
    public fun <T> ApiResponse<T>.mapFailure(): ApiResponse<T> {
      val mappers = SandwichInitializer.sandwichFailureMappers
      var response: ApiResponse<T> = this
      mappers.forEach { mapper ->
        if (response is Failure) {
          if (mapper is ApiResponseFailureMapper) {
            response = mapper.map(response as Failure<T>) as ApiResponse<T>
          } else if (mapper is ApiResponseFailureSuspendMapper) {
            val scope = SandwichInitializer.sandwichScope
            scope.launch {
              response = mapper.map(response as Failure<T>) as ApiResponse<T>
            }
          }
        }
      }
      return response
    }
  }
}
