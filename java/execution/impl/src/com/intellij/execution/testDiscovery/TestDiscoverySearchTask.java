/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.execution.testDiscovery;

import com.intellij.codeInsight.actions.FormatChangedTextUtil;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.testframework.SearchForTestsTask;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.diff.FilesTooBigForDiffException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.*;

public abstract class TestDiscoverySearchTask extends SearchForTestsTask {
  private final String myPosition;
  private final String myChangeList;

  public TestDiscoverySearchTask(Project project, ServerSocket socket, String position, String changeList) {
    super(project, socket);
    myPosition = position;
    myChangeList = changeList;
  }
  
  protected abstract void writeFoundPatterns(Set<String> patterns) throws ExecutionException;

  @Override
  protected void search() throws ExecutionException {
    final Project project = getProject();
    final Set<String> patterns = new LinkedHashSet<String>();
    if (myPosition != null) {
      try {
        final Collection<String> testsByMethodName = TestDiscoveryIndex
          .getInstance(project).getTestsByMethodName(myPosition.replace(',', '.'));
        if (testsByMethodName != null) {
          for (String pattern : testsByMethodName) {
            patterns.add(pattern.replace('-', ','));
          }
        }
      }
      catch (IOException ignore) {
      }
    }
    else {
      final List<VirtualFile> files = getAffectedFiles();
      final PsiManager psiManager = PsiManager.getInstance(project);

      for (final VirtualFile file : files) {
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          @Override
          public void run() {
            final PsiFile psiFile = psiManager.findFile(file);
            if (psiFile != null) {
              try {
                final List<TextRange> changedTextRanges = FormatChangedTextUtil.getChangedTextRanges(project, psiFile);
                for (TextRange textRange : changedTextRanges) {
                  final PsiElement start = psiFile.findElementAt(textRange.getStartOffset());
                  final PsiElement end = psiFile.findElementAt(textRange.getEndOffset());
                  final PsiElement parent = PsiTreeUtil.findCommonParent(new PsiElement[]{start, end});
                  final Collection<PsiMethod> methods = new ArrayList<PsiMethod>(
                    PsiTreeUtil.findChildrenOfType(parent, PsiMethod.class));
                  final PsiMethod containingMethod = PsiTreeUtil.getParentOfType(parent, PsiMethod.class);
                  if (containingMethod != null) {
                    methods.add(containingMethod);
                  }
                  for (PsiMethod changedMethod : methods) {
                    final LinkedHashSet<String> detectedPatterns = collectPatterns(changedMethod);
                    if (detectedPatterns != null) {
                      patterns.addAll(detectedPatterns);
                    }
                  }
                }
              }
              catch (FilesTooBigForDiffException ignore) {
              }
            }
          }
        });
      }
    }
    writeFoundPatterns(patterns);
  }

  @NotNull
  private List<VirtualFile> getAffectedFiles() {
    final ChangeListManager changeListManager = ChangeListManager.getInstance(getProject());
    if (myChangeList == null) {
      return changeListManager.getAffectedFiles();
    }
    final LocalChangeList changeList = changeListManager.findChangeList(myChangeList);
    if (changeList != null) {
      List<VirtualFile> files = new ArrayList<VirtualFile>();
      for (Change change : changeList.getChanges()) {
        final ContentRevision afterRevision = change.getAfterRevision();
        if (afterRevision != null) {
          final VirtualFile file = afterRevision.getFile().getVirtualFile();
          if (file != null) {
            files.add(file);
          }
        }
      }
      return files;
    }

    return Collections.emptyList();
  }

  @Override
  protected void onFound() {
  }

  @Nullable
  protected static LinkedHashSet<String> collectPatterns(PsiMethod psiMethod) {
    LinkedHashSet<String> patterns = new LinkedHashSet<String>();
    final PsiClass containingClass = psiMethod.getContainingClass();
    if (containingClass != null) {
      final String qualifiedName = containingClass.getQualifiedName();
      if (qualifiedName != null) {
        final String methodFQN = StringUtil.getQualifiedName(qualifiedName, psiMethod.getName());
        try {
          final Collection<String> testsByMethodName
            = TestDiscoveryIndex.getInstance(containingClass.getProject()).getTestsByMethodName(methodFQN);
          if (testsByMethodName != null) {
            for (String pattern : testsByMethodName) {
              patterns.add(pattern.replace('-', ','));
            }
          }
        }
        catch (IOException e) {
          return null;
        }
      }
    }
    return patterns;
  }
}
