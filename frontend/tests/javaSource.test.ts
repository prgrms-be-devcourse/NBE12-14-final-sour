import test from 'node:test';
import assert from 'node:assert/strict';
import { stripSwagger } from './helpers/javaSource';

test('Swagger 중첩·여러 줄·문자열 괄호만 제거하고 검증 어노테이션은 보존한다', () => {
  const source = `@Schema(description = "a)b\\\"c", implementation = Wrapper(value = Nested(x = "(")))
@ArraySchema(
  schema = @Schema(example = "x)y")
)
@NotBlank @Size(max = 20) String name;
String literal = "@Schema(fake)"; // @Schema(comment)
@Parameter(description = "value") @Min(1) @Max(300) int limit;
void send(@Valid @RequestBody Body body, @Header("simpSessionId") String session);`;
  const result = stripSwagger(source);
  assert.match(result, /@NotBlank @Size\(max = 20\) String name/);
  assert.match(result, /@Min\(1\) @Max\(300\) int limit/);
  // Spring binding annotations share names with Swagger ones and must survive.
  assert.match(result, /@Valid @RequestBody Body body, @Header\("simpSessionId"\) String session/);
  assert.match(result, /"@Schema\(fake\)"; \/\/ @Schema\(comment\)/);
  assert.doesNotMatch(result, /@ArraySchema|@Parameter|implementation = Wrapper/);
  assert.throws(() => stripSwagger('@Schema(example = "x"'), /Unclosed/);
});
