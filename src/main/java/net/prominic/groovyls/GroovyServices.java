////////////////////////////////////////////////////////////////////////////////
// Copyright 2022 Prominic.NET, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
//
// Author: Prominic.NET, Inc.
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package net.prominic.groovyls;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

import groovy.lang.GroovyClassLoader;
import groovyjarjarasm.asm.Opcodes;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassGraphException;
import io.github.classgraph.ScanResult;
import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.control.GroovyLSCompilationUnit;
import net.prominic.groovyls.config.ICompilationUnitFactory;
import net.prominic.groovyls.gdsl.GdslSymbolsManager;
import net.prominic.groovyls.providers.CompletionProvider;
import net.prominic.groovyls.providers.DefinitionProvider;
import net.prominic.groovyls.providers.DocumentSymbolProvider;
import net.prominic.groovyls.providers.HoverProvider;
import net.prominic.groovyls.providers.ReferenceProvider;
import net.prominic.groovyls.providers.RenameProvider;
import net.prominic.groovyls.providers.SignatureHelpProvider;
import net.prominic.groovyls.providers.TypeDefinitionProvider;
import net.prominic.groovyls.providers.WorkspaceSymbolProvider;
import net.prominic.groovyls.providers.SemanticTokensProvider;
import net.prominic.groovyls.util.FileContentsTracker;
import net.prominic.groovyls.util.GroovyLanguageServerUtils;
import net.prominic.lsp.utils.Positions;

public class GroovyServices implements TextDocumentService, WorkspaceService, LanguageClientAware {
	private static final Pattern PATTERN_CONSTRUCTOR_CALL = Pattern.compile(".*new \\w*$");

	private LanguageClient languageClient;

	private Path workspaceRoot;
	private ICompilationUnitFactory compilationUnitFactory;
	private GroovyLSCompilationUnit compilationUnit;
	private ASTNodeVisitor astVisitor;
	private Map<URI, List<Diagnostic>> prevDiagnosticsByFile;
	private FileContentsTracker fileContentsTracker = new FileContentsTracker();
	private ScanResult classGraphScanResult = null;
	private GroovyClassLoader classLoader = null;
	private URI previousContext = null;
	private GdslSymbolsManager gdslSymbolsManager = new GdslSymbolsManager();
	private SemanticTokensProvider semanticTokensProvider = null;

	public GroovyServices(ICompilationUnitFactory factory) {
		compilationUnitFactory = factory;
	}

	public void setWorkspaceRoot(Path workspaceRoot) {
		this.workspaceRoot = workspaceRoot;
		gdslSymbolsManager.loadGdslSymbols(workspaceRoot);
		createOrUpdateCompilationUnit();
	}

	@Override
	public void connect(LanguageClient client) {
		languageClient = client;
	}

	// --- NOTIFICATIONS

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		fileContentsTracker.didOpen(params);
		URI uri = URI.create(params.getTextDocument().getUri());
		compileAndVisitAST(uri);
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		fileContentsTracker.didChange(params);
		URI uri = URI.create(params.getTextDocument().getUri());
		compileAndVisitAST(uri);
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		fileContentsTracker.didClose(params);
		URI uri = URI.create(params.getTextDocument().getUri());
		compileAndVisitAST(uri);
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {
		// nothing to handle on save at this time
	}

	@Override
	public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
		boolean isSameUnit = createOrUpdateCompilationUnit();
		Set<URI> urisWithChanges = params.getChanges().stream().map(fileEvent -> URI.create(fileEvent.getUri()))
				.collect(Collectors.toSet());
		compile();
		if (isSameUnit) {
			visitAST(urisWithChanges);
		} else {
			visitAST();
		}
	}

	@Override
	public void didChangeConfiguration(DidChangeConfigurationParams params) {
		if (!(params.getSettings() instanceof JsonObject)) {
			return;
		}
		JsonObject settings = (JsonObject) params.getSettings();
		this.updateClasspath(settings);
	}

	private void updateClasspath(JsonObject settings) {
		List<String> classpathList = new ArrayList<>();

		if (settings.has("groovy") && settings.get("groovy").isJsonObject()) {
			JsonObject groovy = settings.get("groovy").getAsJsonObject();
			if (groovy.has("classpath") && groovy.get("classpath").isJsonArray()) {
				JsonArray classpath = groovy.get("classpath").getAsJsonArray();
				classpath.forEach(element -> {
					classpathList.add(element.getAsString());
				});
			}
		}

		if (!classpathList.equals(compilationUnitFactory.getAdditionalClasspathList())) {
			compilationUnitFactory.setAdditionalClasspathList(classpathList);

			createOrUpdateCompilationUnit();
			compile();
			visitAST();
			previousContext = null;
		}
	}

	// --- REQUESTS

	@Override
	public CompletableFuture<Hover> hover(HoverParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		recompileIfContextChanged(uri);

		HoverProvider provider = new HoverProvider(astVisitor);
		return provider.provideHover(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
		TextDocumentIdentifier textDocument = params.getTextDocument();
		Position position = params.getPosition();
		URI uri = URI.create(textDocument.getUri());

		recompileIfContextChanged(uri);

		String originalSource = null;
		ASTNode offsetNode = astVisitor.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
		if (offsetNode == null) {
			originalSource = fileContentsTracker.getContents(uri);
			VersionedTextDocumentIdentifier versionedTextDocument = new VersionedTextDocumentIdentifier(
					textDocument.getUri(), 1);
			int offset = Positions.getOffset(originalSource, position);
			String lineBeforeOffset = originalSource.substring(offset - position.getCharacter(), offset);
			Matcher matcher = PATTERN_CONSTRUCTOR_CALL.matcher(lineBeforeOffset);
			TextDocumentContentChangeEvent changeEvent = null;
			if (matcher.matches()) {
				changeEvent = new TextDocumentContentChangeEvent(new Range(position, position), 0, "a()");
			} else {
				changeEvent = new TextDocumentContentChangeEvent(new Range(position, position), 0, "a");
			}
			DidChangeTextDocumentParams didChangeParams = new DidChangeTextDocumentParams(versionedTextDocument,
					Collections.singletonList(changeEvent));
			// if the offset node is null, there is probably a syntax error.
			// a completion request is usually triggered by the . character, and
			// if there is no property name after the dot, it will cause a syntax
			// error.
			// this hack adds a placeholder property name in the hopes that it
			// will correctly create a PropertyExpression to use for completion.
			// we'll restore the original text after we're done handling the
			// completion request.
			didChange(didChangeParams);
		}

		CompletableFuture<Either<List<CompletionItem>, CompletionList>> result = null;
		try {
			CompletionProvider provider = new CompletionProvider(astVisitor, classGraphScanResult);
			result = provider.provideCompletion(params.getTextDocument(), params.getPosition(), params.getContext());
		} finally {
			if (originalSource != null) {
				VersionedTextDocumentIdentifier versionedTextDocument = new VersionedTextDocumentIdentifier(
						textDocument.getUri(), 1);
				TextDocumentContentChangeEvent changeEvent = new TextDocumentContentChangeEvent(null, 0,
						originalSource);
				DidChangeTextDocumentParams didChangeParams = new DidChangeTextDocumentParams(versionedTextDocument,
						Collections.singletonList(changeEvent));
				didChange(didChangeParams);
			}
		}

		return result;
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
			DefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		recompileIfContextChanged(uri);

		DefinitionProvider provider = new DefinitionProvider(astVisitor);
		return provider.provideDefinition(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
		TextDocumentIdentifier textDocument = params.getTextDocument();
		Position position = params.getPosition();
		URI uri = URI.create(textDocument.getUri());

		recompileIfContextChanged(uri);

		String originalSource = null;
		ASTNode offsetNode = astVisitor.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
		if (offsetNode == null) {
			originalSource = fileContentsTracker.getContents(uri);
			VersionedTextDocumentIdentifier versionedTextDocument = new VersionedTextDocumentIdentifier(
					textDocument.getUri(), 1);
			TextDocumentContentChangeEvent changeEvent = new TextDocumentContentChangeEvent(
					new Range(position, position), 0, ")");
			DidChangeTextDocumentParams didChangeParams = new DidChangeTextDocumentParams(versionedTextDocument,
					Collections.singletonList(changeEvent));
			// if the offset node is null, there is probably a syntax error.
			// a signature help request is usually triggered by the ( character,
			// and if there is no matching ), it will cause a syntax error.
			// this hack adds a placeholder ) character in the hopes that it
			// will correctly create a ArgumentListExpression to use for
			// signature help.
			// we'll restore the original text after we're done handling the
			// signature help request.
			didChange(didChangeParams);
		}

		try {
			SignatureHelpProvider provider = new SignatureHelpProvider(astVisitor);
			return provider.provideSignatureHelp(params.getTextDocument(), params.getPosition());
		} finally {
			if (originalSource != null) {
				VersionedTextDocumentIdentifier versionedTextDocument = new VersionedTextDocumentIdentifier(
						textDocument.getUri(), 1);
				TextDocumentContentChangeEvent changeEvent = new TextDocumentContentChangeEvent(null, 0,
						originalSource);
				DidChangeTextDocumentParams didChangeParams = new DidChangeTextDocumentParams(versionedTextDocument,
						Collections.singletonList(changeEvent));
				didChange(didChangeParams);
			}
		}
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
			TypeDefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		recompileIfContextChanged(uri);

		TypeDefinitionProvider provider = new TypeDefinitionProvider(astVisitor);
		return provider.provideTypeDefinition(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		recompileIfContextChanged(uri);

		ReferenceProvider provider = new ReferenceProvider(astVisitor);
		return provider.provideReferences(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
			DocumentSymbolParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		recompileIfContextChanged(uri);

		DocumentSymbolProvider provider = new DocumentSymbolProvider(astVisitor);
		return provider.provideDocumentSymbols(params.getTextDocument());
	}

	@Override
	public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
		TextDocumentIdentifier textDocument = params.getTextDocument();
		URI uri = URI.create(textDocument.getUri());
		recompileIfContextChanged(uri);

		// Ensure semantic tokens provider is initialized
		if (semanticTokensProvider == null) {
			semanticTokensProvider = new SemanticTokensProvider(fileContentsTracker, astVisitor);
		}

		// Provide semantic tokens - GDSL symbols are injected before LSP transmission
		return CompletableFuture.completedFuture(semanticTokensProvider.provideFull(textDocument));
	}

	@Override
	public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
		WorkspaceSymbolProvider provider = new WorkspaceSymbolProvider(astVisitor);
		return provider.provideWorkspaceSymbols(params.getQuery());
	}

	@Override
	public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		recompileIfContextChanged(uri);

		RenameProvider provider = new RenameProvider(astVisitor, fileContentsTracker);
		return provider.provideRename(params);
	}

	// --- INTERNAL

	/**
	 * Resolves a Maven package using the user's home ~/.m2 repository and returns a
	 * list of absolute JAR paths.
	 */
	public static List<String> downloadToDefaultM2(String coords, String remoteRepoUrl) throws Exception {
		// 1. Initialize engines
		DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
		locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
		locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
		RepositorySystem system = locator.getService(RepositorySystem.class);

		// 2. Point strictly to the global user home ~/.m2/repository across all OS
		// environments
		DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
		File m2Home = new File(System.getProperty("user.home"), ".m2/repository");
		LocalRepository localRepo = new LocalRepository(m2Home);
		session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

		// 3. Define artifact details
		Artifact artifact = new DefaultArtifact(coords);
		Dependency dependency = new Dependency(artifact, "runtime");
		RemoteRepository remoteRepo = new RemoteRepository.Builder("custom-repo", "default", remoteRepoUrl).build();

		// 4. Assemble requests
		CollectRequest collectRequest = new CollectRequest();
		collectRequest.setRoot(dependency);
		collectRequest.setRepositories(Collections.singletonList(remoteRepo));

		DependencyRequest dependencyRequest = new DependencyRequest();
		dependencyRequest.setCollectRequest(collectRequest);

		System.out.println("Checking / Resolving " + coords + " from " + remoteRepoUrl);
		DependencyResult result = system.resolveDependencies(session, dependencyRequest);

		// 5. Gather and return absolute paths for found runtime JAR files
		return result.getArtifactResults().stream()
				.map(ArtifactResult::getArtifact)
				.map(a -> a.getFile().getAbsolutePath())
				.filter(path -> path.endsWith(".jar"))
				.collect(Collectors.toList());
	}

	public static void updateVscodeSettings(Collection<String> jarPaths) {
		Path settingsPath = Paths.get(".vscode", "settings.json");
		File settingsFile = settingsPath.toFile();

		JsonObject rootObject;

		try {
			// 1. Ensure directories exist
			Files.createDirectories(settingsPath.getParent());

			// 2. Read existing file or initialize a clean JSON object container
			if (settingsFile.exists() && settingsFile.length() > 0) {
				try (FileReader reader = new FileReader(settingsFile)) {
					rootObject = JsonParser.parseReader(reader).getAsJsonObject();
				} catch (Exception e) {
					// Fallback if file is corrupted or invalid JSON format
					rootObject = new JsonObject();
				}
			} else {
				rootObject = new JsonObject();
			}

			// 3. Build up the structured JSON Array natively
			JsonArray classpathArray = new JsonArray();
			classpathArray.add(".");
			for (String path : jarPaths) {
				classpathArray.add(path); // GSON handles backslash escaping internally!
			}

			// 4. Safely set or overwrite the property key context
			rootObject.add("groovy.classpath", classpathArray);

			// 5. Serialize back down to disk unformatted
			// (Use new GsonBuilder().setPrettyPrinting().create() if you want it pretty!)
			Gson gson = new Gson();
			try (JsonWriter jsonWriter = new JsonWriter(new FileWriter(settingsFile))) {
				jsonWriter.setIndent("\t");
				gson.toJson(rootObject, jsonWriter);
			}

			System.out.println("Successfully integrated classpath map into .vscode/settings.json via GSON.");

		} catch (IOException e) {
			System.err.println("GSON file interaction failure: " + e.getMessage());
		}
	}

	// This is only called once on LS startup (only time when astVisitor is null).
	private void visitAST() {
		if (compilationUnit == null) {
			return;
		}
		astVisitor = new ASTNodeVisitor();
		astVisitor.visitCompilationUnit(compilationUnit);

		installJenkinsGDSLDependencies();
		injectDefaultGroovyMethods();

		// Inject GDSL symbols as methods into ClassNodes so they're available
		// through normal AST queries in providers
		gdslSymbolsManager.injectGdslSymbolsIntoClassNodes(astVisitor.getClassNodes(), compilationUnit.getClassLoader());
	}

	// This is run on EVERY CHANGE to EVERY GROOVY FILE in the workspace.
	private void visitAST(Set<URI> uris) {
		if (astVisitor == null) {
			visitAST();
			return;
		}
		if (compilationUnit == null) {
			return;
		}
		astVisitor.visitCompilationUnit(compilationUnit, uris);

		// Inject GDSL symbols as methods into ClassNodes so they're available
		// through normal AST queries in providers
		gdslSymbolsManager.injectGdslSymbolsIntoClassNodes(astVisitor.getClassNodes(), compilationUnit.getClassLoader());
	}

	private void installJenkinsGDSLDependencies() {
		// Use a LinkedHashSet to eliminate duplicate dependencies across downstream
		// definitions
		Set<String> allJarPaths = new HashSet<>();
		String jenkinsRepo = "https://repo.jenkins-ci.org/releases/";

		try {
			allJarPaths.addAll(downloadToDefaultM2(
					"org.jenkinsci.plugins:pipeline-model-definition:1.9.3", jenkinsRepo));
			allJarPaths.addAll(downloadToDefaultM2("org.kohsuke.stapler:stapler:1.178", jenkinsRepo));
			allJarPaths.addAll(downloadToDefaultM2("org.jenkins-ci.main:jenkins-core:2.289.3", jenkinsRepo));

			// Inject paths directly into settings.json properties
			updateVscodeSettings(allJarPaths);

		} catch (Exception e) {
			System.err.println("Failed programmatic dependency resolution step.");
			e.printStackTrace();
		}
	}

	private void injectDefaultGroovyMethods() {
		for (Class<?> dgmClass : DefaultGroovyMethods.DGM_LIKE_CLASSES) {
			for (Method method : dgmClass.getMethods()) {
				if (method.getParameterCount() == 0)
					continue;
				Class<?> firstParameterType = method.getParameterTypes()[0];
				if (firstParameterType == Object.class) {
					MethodNode mn = ClassHelper.OBJECT_TYPE.addMethod(
							method.getName(),
							Opcodes.ACC_PUBLIC,
							ClassHelper.make(method.getReturnType()),
							Stream.of(method.getParameters()).skip(1)
									.map(p -> new Parameter(ClassHelper.make(p.getType()), p.getName()))
									.toArray(Parameter[]::new),
							Stream.of(method.getExceptionTypes()).map(ClassHelper::make).toArray(ClassNode[]::new),
							null);
					mn.putNodeMetaData("dgm", true);
				}
			}
		}
	}

	private boolean createOrUpdateCompilationUnit() {
		if (compilationUnit != null) {
			File targetDirectory = compilationUnit.getConfiguration().getTargetDirectory();
			if (targetDirectory != null && targetDirectory.exists()) {
				try {
					Files.walk(targetDirectory.toPath()).sorted(Comparator.reverseOrder()).map(Path::toFile)
							.forEach(File::delete);
				} catch (IOException e) {
					System.err.println("Failed to delete target directory: " + targetDirectory.getAbsolutePath());
					compilationUnit = null;
					return false;
				}
			}
		}

		GroovyLSCompilationUnit oldCompilationUnit = compilationUnit;
		compilationUnit = compilationUnitFactory.create(workspaceRoot, fileContentsTracker);
		fileContentsTracker.resetChangedFiles();

		if (compilationUnit != null) {
			File targetDirectory = compilationUnit.getConfiguration().getTargetDirectory();
			if (targetDirectory != null && !targetDirectory.exists() && !targetDirectory.mkdirs()) {
				System.err.println("Failed to create target directory: " + targetDirectory.getAbsolutePath());
			}
			GroovyClassLoader newClassLoader = compilationUnit.getClassLoader();
			if (!newClassLoader.equals(classLoader)) {
				classLoader = newClassLoader;

				try {
					classGraphScanResult = new ClassGraph().overrideClassLoaders(classLoader).enableClassInfo()
							.enableSystemJarsAndModules()
							.scan();
				} catch (ClassGraphException e) {
					classGraphScanResult = null;
				}
			}
		} else {
			classGraphScanResult = null;
		}

		return compilationUnit != null && compilationUnit.equals(oldCompilationUnit);
	}

	protected void recompileIfContextChanged(URI newContext) {
		if (previousContext == null || previousContext.equals(newContext)) {
			return;
		}
		fileContentsTracker.forceChanged(newContext);
		compileAndVisitAST(newContext);
	}

	private void compileAndVisitAST(URI contextURI) {
		Set<URI> uris = Collections.singleton(contextURI);
		boolean isSameUnit = createOrUpdateCompilationUnit();
		compile();
		if (isSameUnit) {
			visitAST(uris);
		} else {
			visitAST();
		}
		previousContext = contextURI;
	}

	private void compile() {
		if (compilationUnit == null) {
			return;
		}
		try {
			// AST is completely built after the canonicalization phase
			// for code intelligence, we shouldn't need to go further
			// http://groovy-lang.org/metaprogramming.html#_compilation_phases_guide
			compilationUnit.compile(Phases.CANONICALIZATION);
		} catch (CompilationFailedException e) {
			// ignore
		} catch (GroovyBugError e) {
			System.err.println("Unexpected exception in language server when compiling Groovy.");
			e.printStackTrace(System.err);
		} catch (Exception e) {
			System.err.println("Unexpected exception in language server when compiling Groovy.");
			e.printStackTrace(System.err);
		}
		Set<PublishDiagnosticsParams> diagnostics = handleErrorCollector(compilationUnit.getErrorCollector());
		diagnostics.stream().forEach(languageClient::publishDiagnostics);
	}

	private Set<PublishDiagnosticsParams> handleErrorCollector(ErrorCollector collector) {
		Map<URI, List<Diagnostic>> diagnosticsByFile = new HashMap<>();

		List<? extends Message> errors = collector.getErrors();
		if (errors != null) {
			errors.stream().filter((Object message) -> message instanceof SyntaxErrorMessage)
					.forEach((Object message) -> {
						SyntaxErrorMessage syntaxErrorMessage = (SyntaxErrorMessage) message;
						SyntaxException cause = syntaxErrorMessage.getCause();
						Range range = GroovyLanguageServerUtils.syntaxExceptionToRange(cause);
						if (range == null) {
							// range can't be null in a Diagnostic, so we need
							// a fallback
							range = new Range(new Position(0, 0), new Position(0, 0));
						}
						Diagnostic diagnostic = new Diagnostic();
						diagnostic.setRange(range);
						diagnostic.setSeverity(cause.isFatal() ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning);
						diagnostic.setMessage(cause.getMessage());
						URI uri = Paths.get(cause.getSourceLocator()).toUri();
						diagnosticsByFile.computeIfAbsent(uri, (key) -> new ArrayList<>()).add(diagnostic);
					});
		}

		Set<PublishDiagnosticsParams> result = diagnosticsByFile.entrySet().stream()
				.map(entry -> new PublishDiagnosticsParams(entry.getKey().toString(), entry.getValue()))
				.collect(Collectors.toSet());

		if (prevDiagnosticsByFile != null) {
			for (URI key : prevDiagnosticsByFile.keySet()) {
				if (!diagnosticsByFile.containsKey(key)) {
					// send an empty list of diagnostics for files that had
					// diagnostics previously or they won't be cleared
					result.add(new PublishDiagnosticsParams(key.toString(), new ArrayList<>()));
				}
			}
		}
		prevDiagnosticsByFile = diagnosticsByFile;
		return result;
	}
}