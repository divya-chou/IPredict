package ca.ipredict.predictor.CPT;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import ca.ipredict.database.Item;
import ca.ipredict.database.Sequence;
import ca.ipredict.helpers.MemoryLogger;
import ca.ipredict.predictor.Parameters;
import ca.ipredict.predictor.Predictor;

/**
 * Predictor based on a 3 main structures
 * a prediction tree, an inverted index and a lookup table
 * @author Ted Gueniche
 */
public class CPTPredictor implements Predictor {

	private PredictionTree Root; //prediction tree
	private Map<Integer, PredictionTree> LT; //Lookup Table
	private Map<Integer, Bitvector> II;
	
	private long nodeNumber; //number of node in the prediction tree
	
	private List<Sequence> mTrainingSequences; //list of sequences to test
	
	/**
	 * Set the training set
	 */
	public void setTrainingSequences(List<Sequence> trainingSequences) {
		mTrainingSequences = trainingSequences;
	}
	
	/**
	 * Finds all branches that contains this sequences
	 * @param targetArray sequence to find in the tree.
	 * @return List of sequence id ( can be transformed into leafs ) as a bitset
	 */
	private Bitvector getMatchingSequences(Item[] targetArray) {
		//find all sequences that have all the target's items
		//for each item in the target sequence
		Bitvector intersection = null;
		
		//if there is only one item in target,then no intersection needed
		if(targetArray.length == 1){
			intersection = II.get(targetArray[0].val);
		}
		//Do the intersection of all the target's items bitsets
		else { 
			//for each item in the target
			for(int i = 0 ; i < targetArray.length; i++) {
				
				Bitvector bitset = II.get(targetArray[i].val);
				if(bitset != null){
					//if it's the first item, then no intersection needed
					if(intersection == null){
						intersection = (Bitvector) bitset.clone();
					}
					//the actual intersection
					else {
						intersection.and(bitset);
					}
				}
			}
		}
		
		//if intersection is empty (no sequences contained all target's item)
		if(intersection == null || intersection.cardinality() == 0)
			return new Bitvector(); //no match

		//return the resulting bitset
		return intersection;
	}

	/**
	 * Updates a CountTable based on a given sequence using the predictionTree
	 * @param targetArray Sequence to use
	 * @param weight Weight to add for each occurrence of an item in the CountTable
	 * @param CountTable The CountTable to update/fill
	 * @param hashSidVisited Prevent for processing the same branch multiple times
	 */
	private void UpdateCountTable(Item[] targetArray, float weight, Map<Integer, Float> CountTable, HashSet<Integer> hashSidVisited) {
		
		Bitvector indexes = getMatchingSequences(targetArray); 
		
		//if there is not set of sequences ids containing all the target's items together
		if(indexes.cardinality() == 0){
			return;
		}
	
		//creating an HashMap of the target's item (for O(1) search time)
		HashSet<Integer> hashTarget = new HashSet<Integer>(targetArray.length); // PHIL08: Initialise la taille avec: target.length;
		for(Item it : targetArray) {
			hashTarget.add(it.val);
		}
		
		//For each branch 
		for(int index = indexes.nextSetBit(0); index >= 0 ; index = indexes.nextSetBit(index+1)) {

			//Skip branches that have already been seen for this target sequence
			if(Parameters.useHashSidVisited && hashSidVisited.contains(index)){
				continue;    
			}   
			
			//Getting the branch's leaf
			PredictionTree curNode = LT.get(index);
			
			
			//Transform this branch in a list
			List<Item> branch = new ArrayList<Item>();
			branch.add(curNode.Item); //Adding node to the list
			while(curNode.Parent != null){
				curNode = curNode.Parent; //Going up the tree
				branch.add(curNode.Item); //Adding node to the list
			}
			
			int i = 0;
			
			//Go through the branch (top to bottom) and stop when
			//it has encountered ALL items from the target
			Set<Integer>  alreadySeen = new HashSet<Integer>();  
 			for(i = branch.size()-1 ; i >=0 && alreadySeen.size() != hashTarget.size(); i-- ) { 
 				// if it is an item from target
                 if(hashTarget.contains(branch.get(i).val)) 
                     alreadySeen.add(branch.get(i).val);
 			}
 			int consequentEndPosition = i;
 			
			/*
			//ensure that target's last item is also the last on in the "branch"
			if(Parameters.lastTargetItemShouldAppearLast){
				// Si l'on veut s'assurer que le dernier item de target soit le dernier
				//  appara�ssent en dernier, on va le chercher en premier en parcourant la liste
				// � l'envers.
				Integer lastItem = targetArray[targetArray.length-1].val;
				// for each item in this branch
				for(i = branch.size()-1 ; i >=0; i-- ) { 
     				// if the current item is the last item of target
                     if(lastItem.equals(branch.get(i).val)) {
                    	 break;
                     }
     			}	
				// puis on cherchera les autres items...
			}
			
			// IF items do not need to appear closely (SIMPLE CASE)
            if(Parameters.targetItemsShouldAppearClosely ==0 ){
            	
            	Set<Integer>  alreadySeen = new HashSet<Integer>();  
     			for(i = branch.size()-1 ; i >=0 && alreadySeen.size() != hashTarget.size(); i-- ) { 
     				// if it is an item from target
                     if(hashTarget.contains(branch.get(i).val)) 
                         alreadySeen.add(branch.get(i).val);
     			}
     			
            } // IF items should appear close to each other   (NEED TO DO SOME EXTRA CHECKING)
            else{ 
            	
            	Map<Integer, Integer>  alreadySeen = new HashMap<Integer, Integer>();  
     			for(i = branch.size()-1 ; i >=0 && alreadySeen.size() != hashTarget.size(); i-- ) { 

     				// if it is an item from target
                     if(hashTarget.contains(branch.get(i).val)) {
                     	// record that we have seen the item at the current position i 
                         alreadySeen.put(branch.get(i).val, i);
                      
                         // for each item that we have seen before, if it was too long ago, then forget
                         // that information.
                         Iterator<Entry<Integer, Integer>> iter =  alreadySeen.entrySet().iterator();
                         while (iter.hasNext()) {
                             Map.Entry<java.lang.Integer, java.lang.Integer> entry = (Map.Entry<java.lang.Integer, java.lang.Integer>) iter
                                     .next();
                             if(entry.getValue() < i - hashTarget.size() + Parameters.targetItemsShouldAppearClosely){
                                 iter.remove();
                             }
                         }
                     }
     			}
     			
     			//PHIL08 : IF THE TARGET IS NOT FOUND , THEN RETURN
     			if(alreadySeen.size() != hashTarget.size()){
     				return;
     			}
            }	
            */		
            
            
			//For all the items found 
			for(i = 0; i <= consequentEndPosition; i++) {
				
				float oldValue = 0;
				if(CountTable.containsKey(branch.get(i).val)) {
					oldValue = CountTable.get(branch.get(i).val);
				}

				//Update the countable with the right weight and value
				float curValue = (Parameters.countTableWeightDivided == 0) ? 1f : 1f /((float)indexes.cardinality());
				
				CountTable.put(branch.get(i).val, oldValue + (curValue * weight) );
				
				hashSidVisited.add(index); 
			}
		}
	}
	
	/**
	 * Generate the highest rated sequence from a CountTable using the Lift or the Confidence
	 * @param CountTable The CountTable to use, it needs to be filled
	 * @return The highest rated sequence or an empty one if the CountTable is empty
	 */
	private Sequence getBestSequenceFromCountTable(Map<Integer, Float> CountTable) {
		
		//Looking for the item with the highest count in the CountTable
		double maxValue = -1;
		double secondMaxValue = -1;
		Integer maxItem = -1;
		for(Map.Entry<Integer, Float> it : CountTable.entrySet()) {
			
			//the following measure of confidence and lift are "simplified" but are exactly the same as in the literature.
			//CONFIDENCE : |X -> Y|
			//LIFT: CONFIDENCE(X -> Y) / (|Y|)
			//Calculate score based on lift or confidence
			double lift = it.getValue() / II.get(it.getKey()).cardinality();
			double support = II.get(it.getKey()).cardinality();
			double confidence = it.getValue();
			
			double score = (Parameters.firstVote == 1) ? confidence : lift; //Use confidence or lift, depending on Parameter.firstVote
			
			//Saving the best value
			if(score > maxValue) {
				secondMaxValue = maxValue; //saving the old value as the second best
				maxItem = it.getKey(); //saving the new best value
				maxValue = score;
			} 
			//Savinf the second best value
			else if (score > secondMaxValue) {
				secondMaxValue = score; //updating the second best value
			}
		}

		Sequence predicted = new Sequence(-1);
		
		//Calculating the ratio between the best value and the second best value
		double diff = 1 - (secondMaxValue / maxValue);
		
		//No match
		if(maxItem == -1) {
			//Nothing to do
		} 
		//if there is a max item (at least one item in the CountTable)
		// and it is better than second best (if there is one)
		//and the minTreshold is respected
		else if (diff >= Parameters.voteTreshold || secondMaxValue == -1) {
			Item predictedItem = new Item(maxItem);
			predicted.addItem(predictedItem);
		}
		//if there is multiple "best" items with the same weight
		else if(diff == 0.0 && secondMaxValue != -1) {
			
			//Return the best found value if no Parameters.secondVote
			if(Parameters.secondVote == 0) {
				//Item predictedItem = new Item(maxItem);
				//predicted.addItem(predictedItem);
			}
			//use Support or Lift
			else {
				
				//pick the one with the highest support or lift
				double highestScore = 0;
				int newBestItem = -1;
				for(Map.Entry<Integer, Float> it : CountTable.entrySet()) {
					
					if(maxValue == it.getValue()) {
						if(II.containsKey(it.getKey())) {
							
							double lift = it.getValue() / II.get(it.getKey()).cardinality();
							double support = II.get(it.getKey()).cardinality();
							
							double score = (Parameters.secondVote == 1) ? support : lift; //Use confidence or lift, depending on Parameter.secondVote
							
							if(score > highestScore) {
								highestScore = score;
								newBestItem = it.getKey();
							}
						}
					}
				}			
				Item predictedItem = new Item(newBestItem);
				predicted.addItem(predictedItem);
			}
		}
		else {
			//Nothing to do
		}
			
		return predicted;
	}
	
	/**
	 * Predict the next element in the given sequence
	 * @param sequence to predict
	 */
	public Sequence Predict(Sequence target) {
		
		//remove items that were never seen before from the Target sequence before LLCT try to make a prediction
		//If set to false, those items will be still ignored later on (in updateCountTable())
		if(Parameters.removeUnknownItemsForPrediction){
			Iterator<Item> iter = target.getItems().iterator();
			while (iter.hasNext()) {
				Item item = (Item) iter.next();
				// if there is no bitset for that item (we have never seen it)
				if(II.get(item.val) == null){
					// then remove it from target.
					iter.remove();  
				}
			}
		}
		
		//Convert the target sequence into an array for better performance
		Item[] targetArray = new Item[target.size()];
		for(int i=0; i < target.getItems().size(); i++){
			targetArray[i] = target.get(i);
		}
		int initialTargetArraySize = targetArray.length; // save it to calculate the weight later...

		Sequence prediction = new Sequence(-1);
		int i = 0;
		int minRecursion = Parameters.recursiveDividerMin;
		int maxRecursion = (Parameters.recursiveDividerMax > targetArray.length) ? targetArray.length : Parameters.recursiveDividerMax;
		
		for(i = minRecursion ; i < maxRecursion && prediction.size() == 0; i++) {
			//Reset the CountTable and the hasSidVisited
			HashSet<Integer> hashSidVisited = new HashSet<Integer>();
			Map<Integer, Float> CountTable = new HashMap<Integer, Float>();
			
			int minSize = targetArray.length - i; //setting the minSize for the recursiveDivider
			
			//Dividing the target sequence into sub sequences
			RecursiveDivider(targetArray, minSize, CountTable, hashSidVisited, initialTargetArraySize);
		
			//Getting the best sequence out of the CountTable
			prediction = getBestSequenceFromCountTable(CountTable);
		}
		
		return prediction;
	}
	

	/**
	 * Divides the target sequence into all possible sub sequence with a minimum size of minSize
	 * @param targetArray The initial sequence to divide
	 * @param minSize The minimum size of a sub sequence
	 * @param countTable 
	 * @param hashSidVisited 
	 */
	public void RecursiveDivider(Item[] targetArray, int minSize, Map<Integer, Float> countTable, HashSet<Integer> hashSidVisited , int initialTargetArraySize) {
		
		int size = targetArray.length;
		
		//if the target is small enough or already too small
		if(size <= minSize) {
			return;
		}
		
		//Setting up the weight multiplier for the countTable
		float weight = 1f;		
		if(Parameters.countTableWeightMultiplier == 1)
			weight = 1f  / size;
		else if(Parameters.countTableWeightMultiplier == 2)
			weight = (float)size / initialTargetArraySize;
		
		UpdateCountTable(targetArray, weight, countTable, hashSidVisited);

		//Hiding one item at the time from the target
		for(int toHide = 0; toHide < size; toHide++) {
			
			//Parameter to protect the last sequence's item from being hidden 
//			if(Parameters.dontRemoveLastItemFromTargetByRecursiveDivider && toHide == (size - 2)){
//				continue;
//			}
			
			//Constructing a new sequence from the target without the "toHide" item
			Item[] newSequence = new Item[size -1];
			int currentPosition =0;
			for(int toUse = 0; toUse < size; toUse++) {
				if(toUse != toHide) {
					newSequence[currentPosition++] = targetArray[toUse];
				}
			}
			
			RecursiveDivider(newSequence, minSize, countTable, hashSidVisited, initialTargetArraySize);
		}
	}
	
	@Override
	public String getTAG() {
		return "LFast";
	}
	
	/**
	 * Trains this predictor with training data, use "setTrainingSequences()" first
	 * @return true on success
	 */
	public Boolean Preload() {
		
		nodeNumber = 0;
		int seqId = 0; //current sequence from database
		Root = new PredictionTree();
		LT = new HashMap<Integer, PredictionTree>();
		II = new HashMap<Integer, Bitvector>();
		
		//Logging memory usage
		MemoryLogger.addUpdate();
			
		//Slicing sequences, so no sequence has a length > maxTreeHeight
		List<Sequence> newTrainingSet = new ArrayList<Sequence>();
		for(Sequence seq : mTrainingSequences) {
			
			if(seq.size() > Parameters.splitLength && Parameters.splitMethod > 0) {
				if(Parameters.splitMethod == 1)
					newTrainingSet.addAll(LLCTHelper.sliceBasic(seq, Parameters.splitLength));
				else
					newTrainingSet.addAll(LLCTHelper.slice(seq, Parameters.splitLength));
			}else{
				newTrainingSet.add(seq);
			}	
		}
		mTrainingSequences = newTrainingSet;
		
		
		//For each line (sequence) in file
		for(Sequence curSeq : mTrainingSequences) {
			
			PredictionTree curNode = Root;
			
			//for each item in this sequence
			for(Item it : curSeq.getItems()) {
				
				//if item is not in Inverted Index then we add it
				if(II.containsKey(it.val) == false) {
					Bitvector tmpBitset = new Bitvector();
					II.put(it.val, tmpBitset);
				}
				//updating Inverted Index with seqId for this Item
				
				II.get(it.val).setBitAndIncrementCardinality(seqId);  // PHIL08
				
				//if item is not in prediction tree then we add it
				if(curNode.hasChild(it) == false) {
					curNode.addChild(it);
					nodeNumber++;
				}
				curNode = curNode.getChild(it);
			}
			
			LT.put(seqId, curNode); //adding <sequence id, last node in sequence>
			seqId++; //increment sequence id number
		}

		
		
		/**
		 * OPTIMIZATION:
		 * Removes all the unique items with a really low support from the inverted index.
		 * Should be tested some more, appears to boost the coverage with no significant effect on the precision
		 */
		
		int minSup = 0; //should be relative instead of absolute // for bms try: 50
		Iterator<Entry<Integer, Bitvector>> it = II.entrySet().iterator();  //PHIL08: j'ai enlev� un warning en sp�cifiant le type Entry<Integer, Bitset>>
	    while (it.hasNext()) {
	        Map.Entry<Integer, Bitvector> pairs = it.next();
	        
	        if(pairs.getValue().cardinality() < minSup) {
	        	it.remove();
	        }
	    }
	    
	    /*****************END OF OPTIMIZATION***********************/
		
		//Logging memory usage
		MemoryLogger.addUpdate();

		return true;
	}
	
	/**
	 * Return the number of node in the prediction tree
	 */
	public long size() {
		return nodeNumber;
	}

}
