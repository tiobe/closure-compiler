/*
 * Copyright 2007 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.javascript.jscomp.SourceExcerptProvider.SourceExcerpt.LINE;

import com.google.common.base.Strings;
import com.google.debugging.sourcemap.proto.Mapping.OriginalMapping;
import com.google.javascript.jscomp.SourceExcerptProvider.ExcerptFormatter;
import com.google.javascript.jscomp.SourceExcerptProvider.SourceExcerpt;
import com.google.javascript.rhino.TokenUtil;

/**
 * Lightweight message formatter. The format of messages this formatter
 * produces is very compact and to the point.
 *
 */
public final class LightweightMessageFormatter extends AbstractMessageFormatter {
  private final SourceExcerpt excerpt;
  private static final ExcerptFormatter excerptFormatter =
      new LineNumberingFormatter();
  private boolean includeLocation = true;
  private boolean includeLevel = true;

  /**
   * A constructor for when the client doesn't care about source information.
   */
  private LightweightMessageFormatter() {
    super(null);
    this.excerpt = LINE;
  }

  public LightweightMessageFormatter(SourceExcerptProvider source) {
    this(source, LINE);
  }

  public LightweightMessageFormatter(SourceExcerptProvider source,
      SourceExcerpt excerpt) {
    super(source);
    checkNotNull(source);
    this.excerpt = excerpt;
  }

  public static LightweightMessageFormatter withoutSource() {
    return new LightweightMessageFormatter();
  }

  public LightweightMessageFormatter setIncludeLocation(boolean includeLocation) {
    this.includeLocation = includeLocation;
    return this;
  }

  public LightweightMessageFormatter setIncludeLevel(boolean includeLevel) {
    this.includeLevel = includeLevel;
    return this;
  }

  @Override
  public String formatError(JSError error) {
    return format(error, false);
  }

  @Override
  public String formatWarning(JSError warning) {
    return format(warning, true);
  }

  private String format(JSError error, boolean warning) {
    SourceExcerptProvider source = getSource();
    String sourceName = error.sourceName;
    int lineNumber = error.lineNumber;
    DiagnosticType type = error.getType();

    // Format the non-reverse-mapped position.
    StringBuilder b = new StringBuilder();
    StringBuilder boldLine = new StringBuilder();
    String nonMappedPosition = formatPosition(sourceName, lineNumber);

    // Check if we can reverse-map the source.
    OriginalMapping mapping = source == null ? null : source.getSourceMapping(
        error.sourceName, error.lineNumber, error.getCharno());
    if (mapping == null) {
      boldLine.append(nonMappedPosition);
    } else {
      sourceName = mapping.getOriginalFile();
      lineNumber = mapping.getLineNumber();

      boldLine.append(formatPosition(sourceName, lineNumber));
    }

    boldLine.append(type.key);
    boldLine.append(" - ");

    boldLine.append(error.description.replace("\n", " ").replace("\r", ""));

    b.append(maybeEmbolden(boldLine.toString()));

    return b.toString();
  }

  private static String formatPosition(String sourceName, int lineNumber) {
    StringBuilder b = new StringBuilder();
    if (sourceName != null) {
      b.append(sourceName);
      if (lineNumber > 0) {
        b.append(':');
        b.append(lineNumber);
      }
      b.append(": ");
    }
    return b.toString();
  }

  /**
   * Formats a region by appending line numbers in front, e.g.
   *
   * <pre>
   *    9| if (foo) {
   *   10|   alert('bar');
   *   11| }
   * </pre>
   *
   * and return line excerpt without any modification.
   */
  static class LineNumberingFormatter implements ExcerptFormatter {
    @Override
    public String formatLine(String line, int lineNumber) {
      return line;
    }

    @Override
    public String formatRegion(Region region) {
      if (region == null) {
        return null;
      }
      String code = region.getSourceExcerpt();
      if (code.isEmpty()) {
        return null;
      }

      // max length of the number display
      int numberLength = Integer.toString(region.getEndingLineNumber())
          .length();

      // formatting
      StringBuilder builder = new StringBuilder(code.length() * 2);
      int start = 0;
      int end = code.indexOf('\n', start);
      int lineNumber = region.getBeginningLineNumber();
      while (start >= 0) {
        // line extraction
        String line;
        if (end < 0) {
          line = code.substring(start);
          if (line.isEmpty()) {
            return builder.substring(0, builder.length() - 1);
          }
        } else {
          line = code.substring(start, end);
        }
        builder.append("  ");

        // nice spaces for the line number
        int spaces = numberLength - Integer.toString(lineNumber).length();
        builder.append(Strings.repeat(" ", spaces));
        builder.append(lineNumber);
        builder.append("| ");

        // end & update
        if (end < 0) {
          builder.append(line);
          start = -1;
        } else {
          builder.append(line);
          builder.append('\n');
          start = end + 1;
          end = code.indexOf('\n', start);
          lineNumber++;
        }
      }
      return builder.toString();
    }
  }
}
