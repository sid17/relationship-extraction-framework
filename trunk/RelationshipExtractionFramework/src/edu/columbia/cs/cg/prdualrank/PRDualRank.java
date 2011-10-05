package edu.columbia.cs.cg.prdualrank;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;

import edu.columbia.cs.api.PatternBasedRelationshipExtractor;
import edu.columbia.cs.cg.document.Document;
import edu.columbia.cs.cg.document.TokenizedDocument;
import edu.columbia.cs.cg.document.tokenized.tokenizer.Tokenizer;
import edu.columbia.cs.cg.pattern.Pattern;
import edu.columbia.cs.cg.prdualrank.graph.PRDualRankGraph;
import edu.columbia.cs.cg.prdualrank.graph.generator.ExtractionGraphGenerator;
import edu.columbia.cs.cg.prdualrank.graph.generator.SearchGraphGenerator;
import edu.columbia.cs.cg.prdualrank.index.Index;
import edu.columbia.cs.cg.prdualrank.inference.InferencePRDualRank;
import edu.columbia.cs.cg.prdualrank.inference.convergence.NumberOfIterationsConvergence;
import edu.columbia.cs.cg.prdualrank.inference.quest.MapBasedQuestCalculator;
import edu.columbia.cs.cg.prdualrank.inference.ranking.RankFunction;
import edu.columbia.cs.cg.prdualrank.model.PRDualRankModel;
import edu.columbia.cs.cg.prdualrank.pattern.extractor.PatternExtractor;
import edu.columbia.cs.cg.prdualrank.pattern.extractor.impl.ExtractionPatternExtractor;
import edu.columbia.cs.cg.prdualrank.pattern.extractor.impl.WindowedSearchPatternExtractor;
import edu.columbia.cs.cg.prdualrank.searchengine.SearchEngine;
import edu.columbia.cs.cg.prdualrank.searchengine.querygenerator.QueryGenerator;
import edu.columbia.cs.cg.relations.Entity;
import edu.columbia.cs.cg.relations.Relationship;
import edu.columbia.cs.cg.relations.RelationshipType;
import edu.columbia.cs.cg.relations.constraints.roles.RoleConstraint;
import edu.columbia.cs.cg.relations.entity.matcher.EntityMatcher;
import edu.columbia.cs.engine.Engine;
import edu.columbia.cs.model.Model;
import edu.columbia.cs.og.structure.OperableStructure;
import edu.columbia.cs.og.structure.impl.RelationOperableStructure;
import edu.columbia.cs.utils.MegaCartesianProduct;
import edu.columbia.cs.utils.Words;

public class PRDualRank implements Engine{

	private SearchEngine se;
	private QueryGenerator<String> qg;
	private int k_seed;
	private int span;
	private int ngram;
	private int window;
	private int minsupport;
	private int k_nolabel;
	private int iterations;
	private RankFunction<Pattern<Document,TokenizedDocument>> searchpatternRankFunction;
	private RankFunction<Pattern<Relationship,TokenizedDocument>> extractpatternRankFunction;
	private RankFunction<Relationship> tupleRankFunction;
	private int numberOfPhrases;
	private int extractionPatternLenght;
	private Tokenizer tokenizer;
	private RelationshipType rType;
	private Analyzer myAnalyzer;
	private QueryGenerator<Query> forIndexQueryGenerator;
	
	public PRDualRank(SearchEngine se, QueryGenerator<String> qg, int k_seed, int ngram, int window, int minsupport,
			int k_nolabel, int iterations, int numberOfPhrases, int extractionPatternLenght, RankFunction<Pattern<Document,TokenizedDocument>> searchpatternRankFunction,
			RankFunction<Pattern<Relationship,TokenizedDocument>> extractpatternRankFunction, RankFunction<Relationship> tupleRankFunction, 
			Tokenizer tokenizer, RelationshipType rType, Analyzer myAnalyzer, QueryGenerator<Query> forIndexQueryGenerator,int span){
		this.se = se;
		this.qg = qg;
		this.k_seed = k_seed;
		this.ngram = ngram;
		this.window = window;
		this.minsupport = minsupport;
		this.k_nolabel = k_nolabel;
		this.iterations = iterations;
		this.searchpatternRankFunction = searchpatternRankFunction;
		this.extractpatternRankFunction = extractpatternRankFunction;
		this.tupleRankFunction = tupleRankFunction;
		this.numberOfPhrases = numberOfPhrases;
		this.extractionPatternLenght = extractionPatternLenght;
		this.tokenizer = tokenizer;
		this.rType = rType;
		this.myAnalyzer = myAnalyzer;
		this.forIndexQueryGenerator = forIndexQueryGenerator;
		this.span = span;
		//span is for the relationship type. that comes in the List<OperableStructure>
	}
	
	@Override
	public Model train(List<OperableStructure> list) {
		
		PatternExtractor<Document> spe = new WindowedSearchPatternExtractor<Document>(window, ngram, numberOfPhrases);
		
		PatternExtractor<Relationship> epe = new ExtractionPatternExtractor<Relationship>(span,extractionPatternLenght,rType);
		
		HashMap<Pattern<Document,TokenizedDocument>, Integer> Ps = new HashMap<Pattern<Document,TokenizedDocument>, Integer>();
		
		HashMap<Pattern<Relationship,TokenizedDocument>, Integer> Pe = new HashMap<Pattern<Relationship,TokenizedDocument>, Integer>();
		
		Set<Relationship> seeds = new HashSet<Relationship>();
		
		Set<Relationship> initial = new HashSet<Relationship>();
		
		for (OperableStructure operableStructure : list) {
			
			seeds.add(((RelationOperableStructure)operableStructure).getRelation());
			
			initial.add(((RelationOperableStructure)operableStructure).getRelation());
			
		}
		
		for (Relationship relationship : seeds) {
			
			List<Document> documents = se.search(qg.generateQuery(relationship), k_seed);

			for (Document document : documents) {

				TokenizedDocument tokenizedDocument = new TokenizedDocument(document, tokenizer);
				
				List<Relationship> mathchingRelationships = getMatchingRelationships(tokenizedDocument,relationship);
				
				updateMap(Ps,spe.extractPatterns(tokenizedDocument,relationship,mathchingRelationships));
				
				updateMap(Pe,epe.extractPatterns(tokenizedDocument,relationship,mathchingRelationships));
				
			}
			
		}
		
		Set<Pattern<Document,TokenizedDocument>> searchPatterns = filter(Ps,minsupport);
		
		Set<Pattern<Relationship,TokenizedDocument>> extractPatterns = filter(Pe,minsupport);
		
		PatternBasedRelationshipExtractor<Relationship,TokenizedDocument> pbre = new PatternBasedRelationshipExtractor<Relationship,TokenizedDocument>(extractPatterns);
		
		HashMap<Relationship,Integer> extractedTuples = new HashMap<Relationship,Integer>();
		
		for (Relationship relationship : seeds) {
			
			for (String role : relationship.getRoles()) {
				
				List<Document> documents = se.search(qg.generateQuery(relationship.getRole(role)), k_seed);
				
				for (Document document : documents) {
					
					TokenizedDocument tokenizedDocument = new TokenizedDocument(document, tokenizer);
					
					updateMap(extractedTuples,filterByRole(role,relationship.getRole(role),pbre.extractTuples(tokenizedDocument)));
					
				}
								
			}
			
		}
		
		Set<Relationship> topTuples = filterTopK(extractedTuples,k_nolabel,initial);
		
		Set<TokenizedDocument> documents = new HashSet<TokenizedDocument>();
		
		Index index = new Index(myAnalyzer,true,Words.getStopWords());
				
		for (Relationship relationship : topTuples) {
			
			List<Document> searchResults = se.search(qg.generateQuery(relationship), k_seed);
			
			for (Document document : searchResults) {
				
				TokenizedDocument tokenizedDocument = new TokenizedDocument(document, tokenizer);
				
				documents.add(tokenizedDocument);
	
				index.addDocument(tokenizedDocument);
				
			}
			
		}
		
		index.close();
		
		PRDualRankGraph<Document,TokenizedDocument> Gs = new SearchGraphGenerator<Document,TokenizedDocument>(rType,index,forIndexQueryGenerator).generateGraph(topTuples,searchPatterns,documents);
		
		System.out.println("Extraction Graph Generation");
		
		PRDualRankGraph<Relationship,TokenizedDocument> Ge = new ExtractionGraphGenerator<Relationship,TokenizedDocument>().generateGraph(topTuples,extractPatterns,documents);
				
		InferencePRDualRank<Document,TokenizedDocument> search = new InferencePRDualRank<Document,TokenizedDocument>();
		
		System.out.println("Ranking...");
		
		search.rank(Gs, searchpatternRankFunction, tupleRankFunction, new MapBasedQuestCalculator<Document,TokenizedDocument>(seeds,new NumberOfIterationsConvergence(iterations)));
		
		InferencePRDualRank<Relationship,TokenizedDocument> extract = new InferencePRDualRank<Relationship,TokenizedDocument>();

		System.out.println("Ranking...");
		
		extract.rank(Ge, extractpatternRankFunction, tupleRankFunction, new MapBasedQuestCalculator<Relationship,TokenizedDocument>(seeds,new NumberOfIterationsConvergence(iterations)));
		
		System.out.println("Returning...");
		
		return new PRDualRankModel<Document,Relationship,TokenizedDocument>(search.getRankedPatterns(),extract.getRankedPatterns(),search.getRankedTuples(),extract.getRankedTuples());
		
	}

	private List<Relationship> getMatchingRelationships(TokenizedDocument document,
			Relationship relationship) {
		
		Set<Entity> entities = new HashSet<Entity>(document.getEntities());
		
		Collection<String> roles = relationship.getRoles();
		
		Map<String,Set<Entity>> candidateEntitiesForRole = new HashMap<String,Set<Entity>>();
		
		for(String role : roles){
			
			RoleConstraint roleConstraint = relationship.getRelationshipType().getConstraint(role);
			
			Set<Entity> entitiesForRole = roleConstraint.getCompatibleEntities(entities);
			
			EntityMatcher entityMatcher = relationship.getRelationshipType().getMatchers(role);
			
			Set<Entity> filteredEntitiesForRole = new HashSet<Entity>();
			
			for (Entity entity : entitiesForRole) {
				
				if (entityMatcher.match(relationship.getRole(role), entity)){
					filteredEntitiesForRole.add(entity);
				}
				
			}

			candidateEntitiesForRole.put(role, filteredEntitiesForRole);
		
		}

		List<Relationship> matchingTuples = new ArrayList<Relationship>();
		
		List<Map<String, Entity>> possibilities = MegaCartesianProduct.generateAllPossibilities(candidateEntitiesForRole);
		
		for(Map<String,Entity> candidate : possibilities){
			
			Relationship newRelationship = new Relationship(relationship.getRelationshipType());
			
			for(Entry<String,Entity> entry : candidate.entrySet()){

				newRelationship.setRole(entry.getKey(), entry.getValue());
			
			}

			if (relationship.getRelationshipType().getRelationshipConstraint().checkConstraint(newRelationship)){
				
				matchingTuples.add(newRelationship);
				
			}
			
		}
		
		return matchingTuples;

		
	}

	private class ValueComparator<T> implements Comparator<T>{

		private Map<T, Integer> frequencymap;

		private ValueComparator(Map<T,Integer> frequencymap){
			this.frequencymap = frequencymap;
		}
		
		@Override
		public int compare(T obj1, T obj2) {
			
			return frequencymap.get(obj2).compareTo(frequencymap.get(obj1));
			
		}
		
	}
	
	private <T> Set<T> filterTopK(
			Map<T, Integer> toSelect, int k, Set<T> initial) {
		
		int realLimit = k + initial.size();
		
		SortedMap<T,Integer> sorted = new TreeMap<T, Integer>(new ValueComparator<T>(toSelect));
		
		for (T element : toSelect.keySet()) {
			
			sorted.put(element, toSelect.get(element));
			
		}
		
		for (T element : sorted.keySet()) {
			
			initial.add(element);
			
			if (initial.size() == realLimit)
				break;
		}
		
		return initial;
	}

	private Map<Relationship,Integer> filterByRole(String role,
			Entity value, List<Relationship> extractTuples) {
		
		//TODO I have to use the matcher...
		
		Map<Relationship, Integer> ret = new HashMap<Relationship, Integer>();
		
		for (Relationship relationship : extractTuples) {
			
			if (relationship.getRole(role).equals(value)){
				
				Integer freq = ret.get(relationship);
				
				if (freq == null){
					freq = 0;
				}
				
				ret.put(relationship, freq+1);
			}
			
		}
		
		return ret;
	}

	private <T> Set<T> filter(Map<T, Integer> toFilter, int minsupport) {
		
		Set<T> ret = new HashSet<T>();
		
		for (T pattern : toFilter.keySet()) {

			Integer freq = toFilter.get(pattern);
			
			if (freq >= minsupport){
				ret.add(pattern);
			}
			
		}

		return ret;
		
	}

	private <T> void updateMap(Map<T, Integer> acc,
			Map<T, Integer> actual) {
		
		for (T pattern : actual.keySet()) {
			
			Integer freq = acc.get(pattern);
			
			if (freq == null){
				
				freq = 0;
				
			}
			
			acc.put(pattern, freq + actual.get(pattern));
			
		}
		
	}
	
}
